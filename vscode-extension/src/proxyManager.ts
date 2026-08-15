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
    private readonly outputChannel: vscode.OutputChannel;
    private readonly onStatusChangeEmitter = new vscode.EventEmitter<ProxyStatus>();
    readonly onStatusChange = this.onStatusChangeEmitter.event;

    constructor(private readonly context: vscode.ExtensionContext) {
        this.outputChannel = vscode.window.createOutputChannel('ContextCompresso');
    }

    getStatus(): ProxyStatus {
        return {
            running: this.process !== null && !this.process.killed,
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
        if (this.process && !this.process.killed) {
            return;
        }
        this.lastError = null;

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
        const port = await this.findFreePort(preferredPort);
        this.resolvedPort = port;

        this.outputChannel.appendLine(`[info] starting: ${javaPath} -jar ${jarPath} --server.port=${port}`);
        const dataDir = path.join(this.context.globalStorageUri.fsPath, 'data');
        fs.mkdirSync(dataDir, { recursive: true });

        this.process = cp.spawn(javaPath, [
            '-jar', jarPath,
            `--server.port=${port}`,
            `--contextcompresso.ccr.db-path=${path.join(dataDir, 'ccr.db')}`
        ], { stdio: ['ignore', 'pipe', 'pipe'] });

        this.process.stdout?.on('data', (chunk) => this.outputChannel.append(chunk.toString()));
        this.process.stderr?.on('data', (chunk) => this.outputChannel.append(chunk.toString()));
        this.process.on('exit', (code) => {
            this.outputChannel.appendLine(`[info] proxy exited with code ${code}`);
            this.process = null;
            this.emitStatus();
        });
        this.process.on('error', (err) => {
            this.lastError = `Failed to launch proxy: ${err.message}`;
            this.outputChannel.appendLine(`[error] ${this.lastError}`);
            this.process = null;
            this.emitStatus();
        });

        this.emitStatus();
        const healthy = await this.waitForHealth(port, 30_000);
        if (!healthy) {
            this.lastError = 'Proxy did not become healthy within 30s. Check ContextCompresso output for details.';
            this.outputChannel.appendLine(`[error] ${this.lastError}`);
        }
        this.emitStatus();
    }

    async stop(): Promise<void> {
        if (this.process && !this.process.killed) {
            this.process.kill();
        }
        this.process = null;
        this.emitStatus();
    }

    async restart(): Promise<void> {
        await this.stop();
        await this.start();
    }

    dispose(): void {
        this.process?.kill();
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
                tester.close(() => resolve(preferred));
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
