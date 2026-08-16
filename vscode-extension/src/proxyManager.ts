import * as vscode from 'vscode';
import * as cp from 'child_process';
import * as fs from 'fs';
import * as net from 'net';
import * as path from 'path';
import * as http from 'http';

export interface ProxyStatus {
    running: boolean;
    port: number | null;
    pid: number | null;
    lastError: string | null;
}

/**
 * Owns the lifecycle of the bundled ContextCompresso jar: locating a Java 17+ runtime,
 * picking a free port (never assuming the configured one is available — a previous crashed
 * instance or an unrelated process may already hold it), spawning the process, and polling
 * /actuator/health before reporting it ready. Mirrors the fail-open spirit of the proxy
 * itself: if anything here fails, the extension must degrade to "proxy not running" rather
 * than throwing into VS Code's activation path.
 */
export class ProxyManager implements vscode.Disposable {
    private process: cp.ChildProcess | null = null;
    private resolvedPort: number | null = null;
    private lastError: string | null = null;
    // True when this window is using a proxy owned (spawned) by another window rather than
    // by `process` here. Adopted mode never kills the shared proxy on stop/dispose, and has
    // no 'exit' event to rely on, so a poller fills that gap — see adoptedHealthPoll.
    private adopted = false;
    private adoptedHealthPollHandle: ReturnType<typeof setInterval> | null = null;
    private readonly outputChannel: vscode.OutputChannel;
    private readonly onStatusChangeEmitter = new vscode.EventEmitter<ProxyStatus>();
    readonly onStatusChange = this.onStatusChangeEmitter.event;
    // Serializes start()/restart()/stop() so a click while one is still in flight
    // joins the in-progress operation instead of racing its own kill/spawn against it.
    private pending: Promise<void> = Promise.resolve();

    constructor(private readonly context: vscode.ExtensionContext) {
        this.outputChannel = vscode.window.createOutputChannel('ContextCompresso');
    }

    /**
     * running is true if we own a live child process OR we've adopted a healthy proxy owned
     * by another window. resolvedPort is cleared in every place ownership ends (stop, the
     * 'exit'/'error' handlers, adoption loss) so the two can never drift — getBaseUrl() can't
     * hand out a port for a proxy that's no longer there.
     */
    getStatus(): ProxyStatus {
        return {
            running: (this.process !== null && !this.process.killed) || this.adopted,
            port: this.resolvedPort,
            pid: this.process?.pid ?? null,
            lastError: this.lastError
        };
    }

    getBaseUrl(): string | null {
        return this.resolvedPort ? `http://localhost:${this.resolvedPort}` : null;
    }

    showLogs(): void {
        this.outputChannel.show();
    }

    async start(): Promise<void> {
        return this.enqueue(() => this.startInternal());
    }

    private async startInternal(): Promise<void> {
        if ((this.process && !this.process.killed) || this.adopted) {
            return;
        }
        this.lastError = null;

        if (await this.tryAdoptExisting()) {
            return;
        }

        const config = vscode.workspace.getConfiguration('contextcompresso');
        const jarPath = this.resolveJarPath(config.get<string>('jarPath', ''));
        if (!jarPath || !fs.existsSync(jarPath)) {
            this.lastError = `contextcompresso.jar not found at ${jarPath}. Set contextcompresso.jarPath in settings.`;
            this.outputChannel.appendLine(`[error] ${this.lastError}`);
            this.emitStatus();
            return;
        }

        const javaPath = await this.resolveJavaPath(config.get<string>('javaPath', ''));
        if (!javaPath) {
            this.lastError = 'No Java 17+ runtime found. Set contextcompresso.javaPath or install a JDK.';
            this.outputChannel.appendLine(`[error] ${this.lastError}`);
            this.emitStatus();
            return;
        }

        const preferredPort = config.get<number>('port', 8137);
        const started = await this.spawnAndWait(javaPath, jarPath, preferredPort);
        if (!started && preferredPort !== 0) {
            // The port we probed as free lost the race before the JVM could bind it
            // (e.g. a leftover instance from a previous window grabbed it first).
            // Retry once on an OS-assigned ephemeral port rather than surfacing the failure.
            this.outputChannel.appendLine('[info] retrying on an ephemeral port after bind failure');
            await this.spawnAndWait(javaPath, jarPath, 0);
        }
    }

    private async spawnAndWait(javaPath: string, jarPath: string, preferredPort: number): Promise<boolean> {
        const port = await this.findFreePort(preferredPort);
        this.resolvedPort = port;

        this.outputChannel.appendLine(`[info] starting: ${javaPath} -jar ${jarPath} --server.port=${port}`);
        const dataDir = path.join(this.context.globalStorageUri.fsPath, 'data');
        fs.mkdirSync(dataDir, { recursive: true });

        let portBindFailed = false;
        this.process = cp.spawn(javaPath, [
            '-jar', jarPath,
            `--server.port=${port}`,
            `--contextcompresso.ccr.db-path=${path.join(dataDir, 'ccr.db')}`
        ], { stdio: ['ignore', 'pipe', 'pipe'] });

        this.writePidFile(this.process.pid, port);

        this.process.stdout?.on('data', (chunk) => this.outputChannel.append(chunk.toString()));
        this.process.stderr?.on('data', (chunk) => {
            const text = chunk.toString();
            this.outputChannel.append(text);
            if (text.includes('already in use')) {
                portBindFailed = true;
            }
        });
        this.process.on('exit', (code) => {
            this.outputChannel.appendLine(`[info] proxy exited with code ${code}`);
            this.process = null;
            this.resolvedPort = null;
            this.clearPidFile();
            if (!portBindFailed) {
                this.emitStatus();
            }
        });
        this.process.on('error', (err) => {
            this.lastError = `Failed to launch proxy: ${err.message}`;
            this.outputChannel.appendLine(`[error] ${this.lastError}`);
            this.process = null;
            this.resolvedPort = null;
            this.clearPidFile();
            this.emitStatus();
        });

        this.emitStatus();
        const healthy = await this.waitForHealth(port, 30_000);
        if (!healthy) {
            if (portBindFailed) {
                return false;
            }
            this.lastError = 'Proxy did not become healthy within 30s. Check ContextCompresso output for details.';
            this.outputChannel.appendLine(`[error] ${this.lastError}`);
        }
        this.emitStatus();
        return healthy;
    }

    /**
     * Detaches this window from the proxy. If we own the process, stop it; if we merely
     * adopted one owned by another window, just let go — the proxy keeps serving that window.
     */
    async stop(): Promise<void> {
        return this.enqueue(() => this.stopInternal());
    }

    /**
     * Restart is an explicit, deliberate action, so unlike stop() it always tears down
     * whatever proxy is actually on the port — including one owned by another window — and
     * spawns a fresh instance owned by this window. Other windows will re-adopt the new
     * instance the next time their health poll or activation runs.
     */
    async restart(): Promise<void> {
        return this.enqueue(async () => {
            await this.stopInternal();
            const config = vscode.workspace.getConfiguration('contextcompresso');
            const preferredPort = config.get<number>('port', 8137);
            await this.killWhateverIsOnPort(preferredPort);
            this.clearPidFile();
            await this.startInternal();
        });
    }

    /**
     * Runs `op` after any previously-queued start/stop/restart has settled, so overlapping
     * calls (e.g. double-clicking "Restart Proxy") join a single queue instead of racing
     * kill/spawn against each other. A failed op doesn't poison the queue for the next one.
     */
    private enqueue(op: () => Promise<void>): Promise<void> {
        const next = this.pending.catch(() => undefined).then(op);
        this.pending = next.catch(() => undefined);
        return next;
    }

    private async stopInternal(): Promise<void> {
        this.stopAdoptedHealthPoll();
        if (this.adopted) {
            // Not ours to kill — just stop tracking it. The pidfile stays intact for whoever
            // (this window on a future start, or another window) still owns it.
            this.adopted = false;
            this.resolvedPort = null;
            this.emitStatus();
            return;
        }
        if (this.process && !this.process.killed) {
            this.process.kill();
        }
        this.process = null;
        this.resolvedPort = null;
        this.clearPidFile();
        this.emitStatus();
    }

    dispose(): void {
        this.stopAdoptedHealthPoll();
        if (!this.adopted) {
            this.process?.kill();
            this.clearPidFile();
        }
        this.outputChannel.dispose();
    }

    private emitStatus(): void {
        this.onStatusChangeEmitter.fire(this.getStatus());
    }

    private resolveJarPath(configured: string): string {
        if (configured && configured.trim().length > 0) {
            return configured;
        }
        return path.join(this.context.extensionPath, 'resources', 'contextcompresso.jar');
    }

    private async resolveJavaPath(configured: string): Promise<string | null> {
        const candidates: string[] = [];
        if (configured && configured.trim().length > 0) {
            candidates.push(configured);
        }
        if (process.env.JAVA_HOME) {
            candidates.push(path.join(process.env.JAVA_HOME, 'bin', 'java'));
        }
        candidates.push('java'); // rely on PATH

        for (const candidate of candidates) {
            const version = await this.getJavaMajorVersion(candidate);
            if (version !== null && version >= 17) {
                return candidate;
            }
        }
        return null;
    }

    private getJavaMajorVersion(javaPath: string): Promise<number | null> {
        return new Promise((resolve) => {
            cp.execFile(javaPath, ['-version'], (_err, _stdout, stderr) => {
                // `java -version` writes to stderr by convention across all JDK vendors
                const match = stderr.match(/version "(\d+)(?:\.(\d+))?/);
                if (!match) {
                    resolve(null);
                    return;
                }
                const major = parseInt(match[1], 10);
                // pre-JEP 223 versions report as "1.8.0_xxx" — treat the second component as major
                resolve(major === 1 ? parseInt(match[2] ?? '0', 10) : major);
            });
        });
    }

    private pidFilePath(): string {
        return path.join(this.context.globalStorageUri.fsPath, 'proxy.pid');
    }

    private writePidFile(pid: number | undefined, port: number): void {
        if (pid === undefined) {
            return;
        }
        try {
            fs.mkdirSync(this.context.globalStorageUri.fsPath, { recursive: true });
            fs.writeFileSync(this.pidFilePath(), `${pid}:${port}`, 'utf8');
        } catch {
            // best-effort — losing the pid file just means other windows can't adopt or reap it later
        }
    }

    private clearPidFile(): void {
        try {
            fs.rmSync(this.pidFilePath(), { force: true });
        } catch {
            // best-effort
        }
    }

    /**
     * All windows share one proxy: the first window to start spawns it, later windows adopt
     * it instead of racing a spawn of their own. The pidfile (shared via globalStorage) is the
     * handoff point — but a PID found there isn't proof of ownership by itself; it could be a
     * live proxy another window is actively using, or a leftover from a previous session that
     * never shut down cleanly (crash, force-quit, kill -9 on VS Code). Only a health check on
     * the recorded port can tell those apart, so: healthy → adopt, don't touch it; alive but
     * unresponsive → genuinely stale, kill and fall through to spawning our own.
     */
    private async tryAdoptExisting(): Promise<boolean> {
        let recorded: { pid: number; port: number } | null;
        try {
            recorded = this.parsePidFile(fs.readFileSync(this.pidFilePath(), 'utf8'));
        } catch {
            return false;
        }
        if (!recorded) {
            this.clearPidFile();
            return false;
        }
        const { pid, port } = recorded;
        try {
            process.kill(pid, 0); // signal 0: test existence without killing
        } catch {
            this.clearPidFile(); // no such process — stale file from a clean-ish exit
            return false;
        }
        if (!this.looksLikeOurJar(pid)) {
            // pid was recycled by an unrelated process since our process died — don't touch it
            this.clearPidFile();
            return false;
        }
        if (await this.checkHealth(port, 1500)) {
            this.outputChannel.appendLine(`[info] adopting existing proxy owned by another window (pid ${pid}, port ${port})`);
            this.resolvedPort = port;
            this.adopted = true;
            this.startAdoptedHealthPoll();
            this.emitStatus();
            return true;
        }
        this.outputChannel.appendLine(`[info] killing stale/unresponsive proxy process from a previous session (pid ${pid})`);
        try {
            process.kill(pid, 'SIGTERM');
        } catch {
            // process disappeared between the existence check and now — fine
        }
        this.clearPidFile();
        return false;
    }

    /**
     * We don't own a child handle for an adopted proxy, so there's no 'exit' event to tell us
     * when the owning window closes it — poll health instead and drop adoption the moment it
     * stops responding, so this window's status bar doesn't keep claiming a dead proxy is up.
     */
    private startAdoptedHealthPoll(): void {
        this.stopAdoptedHealthPoll();
        this.adoptedHealthPollHandle = setInterval(async () => {
            if (!this.adopted || this.resolvedPort === null) {
                return;
            }
            const healthy = await this.checkHealth(this.resolvedPort, 1500);
            if (!healthy) {
                this.outputChannel.appendLine('[info] adopted proxy is no longer reachable');
                this.adopted = false;
                this.resolvedPort = null;
                this.stopAdoptedHealthPoll();
                this.emitStatus();
            }
        }, 5000);
    }

    private stopAdoptedHealthPoll(): void {
        if (this.adoptedHealthPollHandle) {
            clearInterval(this.adoptedHealthPollHandle);
            this.adoptedHealthPollHandle = null;
        }
    }

    private checkHealth(port: number, timeoutMs: number): Promise<boolean> {
        return new Promise((resolve) => {
            const req = http.get(`http://localhost:${port}/actuator/health`, { timeout: timeoutMs }, (res) => {
                res.resume();
                resolve(res.statusCode === 200);
            });
            req.on('error', () => resolve(false));
            req.on('timeout', () => { req.destroy(); resolve(false); });
        });
    }

    private parsePidFile(text: string): { pid: number; port: number } | null {
        const [pidText, portText] = text.trim().split(':');
        const pid = parseInt(pidText, 10);
        const port = parseInt(portText, 10);
        if (!Number.isInteger(pid) || pid <= 0 || !Number.isInteger(port) || port <= 0) {
            return null;
        }
        return { pid, port };
    }

    /**
     * "Restart Proxy" should mean the configured port is actually free afterward, even if
     * something other than our tracked child (a leftover instance from a previous window, or
     * an unrelated process) is squatting on it. stop() only kills this.process, so this closes
     * the gap by finding and killing whatever the OS says owns the port right now.
     */
    private async killWhateverIsOnPort(port: number): Promise<void> {
        try {
            const pids = process.platform === 'win32'
                ? this.findPidsOnPortWindows(port)
                : cp.execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'], { encoding: 'utf8' })
                    .split('\n').map((s) => parseInt(s.trim(), 10)).filter((n) => Number.isInteger(n));
            for (const pid of pids) {
                this.outputChannel.appendLine(`[info] killing process ${pid} occupying port ${port}`);
                try {
                    process.platform === 'win32'
                        ? cp.execFileSync('taskkill', ['/PID', String(pid), '/F'])
                        : process.kill(pid, 'SIGTERM');
                } catch {
                    // already gone — fine
                }
            }
        } catch {
            // lsof/netstat returns non-zero when nothing is listening — nothing to clean up
        }
    }

    private findPidsOnPortWindows(port: number): number[] {
        const out = cp.execFileSync('netstat', ['-ano', '-p', 'TCP'], { encoding: 'utf8' });
        const pids = new Set<number>();
        for (const line of out.split('\n')) {
            const match = line.match(/^\s*TCP\s+\S*:(\d+)\s+\S+\s+LISTENING\s+(\d+)/);
            if (match && parseInt(match[1], 10) === port) {
                pids.add(parseInt(match[2], 10));
            }
        }
        return [...pids];
    }

    /** Guards against killing an unrelated process whose PID was recycled after ours died. */
    private looksLikeOurJar(pid: number): boolean {
        try {
            const cmd = process.platform === 'win32'
                ? cp.execFileSync('wmic', ['process', 'where', `ProcessId=${pid}`, 'get', 'CommandLine'], { encoding: 'utf8' })
                : cp.execFileSync('ps', ['-p', String(pid), '-o', 'command='], { encoding: 'utf8' });
            return cmd.includes('contextcompresso.jar');
        } catch {
            return false;
        }
    }

    private findFreePort(preferred: number): Promise<number> {
        return new Promise((resolve) => {
            const tester = net.createServer();
            tester.once('error', () => {
                // preferred port is taken — ask the OS for an ephemeral one instead
                const fallback = net.createServer();
                fallback.listen(0, () => {
                    const assigned = (fallback.address() as net.AddressInfo).port;
                    fallback.close(() => resolve(assigned));
                });
            });
            tester.once('listening', () => {
                const assigned = (tester.address() as net.AddressInfo).port;
                tester.close(() => resolve(assigned));
            });
            tester.listen(preferred, '127.0.0.1');
        });
    }

    private waitForHealth(port: number, timeoutMs: number): Promise<boolean> {
        const deadline = Date.now() + timeoutMs;
        return new Promise((resolve) => {
            const attempt = () => {
                const req = http.get(`http://localhost:${port}/actuator/health`, { timeout: 1500 }, (res) => {
                    if (res.statusCode === 200) {
                        res.resume();
                        resolve(true);
                        return;
                    }
                    res.resume();
                    retry();
                });
                req.on('error', retry);
                req.on('timeout', () => { req.destroy(); retry(); });
            };
            const retry = () => {
                if (Date.now() >= deadline) {
                    resolve(false);
                    return;
                }
                setTimeout(attempt, 500);
            };
            attempt();
        });
    }
}
