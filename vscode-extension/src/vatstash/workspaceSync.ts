import * as vscode from 'vscode';
import { ProxyManager, ProxyStatus } from '../proxyManager';
import { VatStashClient } from './vatstashClient';
import { ChangeBatcher, isObviouslyIgnored } from './changeBatcher';
import { IndexStatus } from './types';

const POLL_MS = 5000;

interface FolderEntry {
    folder: vscode.WorkspaceFolder;
    workspaceId: string | null;
    status: IndexStatus | null;
    /** Set by "Clear Index" so the poll does not silently re-create what the user just deleted. */
    cleared: boolean;
    watcher: vscode.FileSystemWatcher;
    batcher: ChangeBatcher;
    subscriptions: vscode.Disposable[];
}

export function vatstashConfig(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('contextcompresso.vatstash');
}

export function isVatStashEnabled(): boolean {
    return vatstashConfig().get<boolean>('enabled', true);
}

/**
 * Keeps the proxy's per-folder indexes in step with the editor: registers every file-scheme
 * workspace folder, forwards file-watcher events as path lists (never contents, the Java side
 * reads from disk), and polls index status for the status bar and dashboard.
 *
 * Registration is idempotent, so it is simply repeated on every transition to running or
 * adopted. That one rule also covers proxy restarts and another window taking the proxy over.
 */
export class WorkspaceSync implements vscode.Disposable {
    private readonly entries = new Map<string, FolderEntry>();
    private readonly statusEmitter = new vscode.EventEmitter<IndexStatus[]>();
    readonly onDidChangeStatus = this.statusEmitter.event;
    private readonly disposables: vscode.Disposable[] = [];
    private pollHandle: ReturnType<typeof setInterval> | null = null;
    private active = false;
    private lastBaseUrl: string | null = null;
    private polling = false;

    constructor(private readonly client: VatStashClient, private readonly proxyManager: ProxyManager) {
        this.disposables.push(
            this.statusEmitter,
            proxyManager.onStatusChange((status) => this.onProxyStatus(status)),
            vscode.workspace.onDidChangeWorkspaceFolders(() => void this.syncFolders()),
            vscode.workspace.onDidChangeConfiguration((e) => {
                if (e.affectsConfiguration('contextcompresso.vatstash.enabled')) {
                    this.onProxyStatus(this.proxyManager.getStatus(), true);
                } else if (e.affectsConfiguration('contextcompresso.vatstash')) {
                    // Exclude globs and size caps are sent at registration; resend them.
                    void this.registerAll();
                }
            })
        );
        this.onProxyStatus(proxyManager.getStatus());
    }

    getWorkspaceIds(): string[] {
        return [...this.entries.values()].map((e) => e.workspaceId).filter((id): id is string => id !== null);
    }

    getStatuses(): IndexStatus[] {
        return [...this.entries.values()].map((e) => e.status).filter((s): s is IndexStatus => s !== null);
    }

    hasFolders(): boolean {
        return this.entries.size > 0;
    }

    /** Reconcile (or, with `full`, rebuild) every folder, re-registering any that were cleared. */
    async reindexAll(full: boolean): Promise<{ ok: number; failed: number; firstError: string | null }> {
        let ok = 0;
        let failed = 0;
        let firstError: string | null = null;
        for (const entry of this.entries.values()) {
            if (entry.cleared || entry.workspaceId === null) {
                entry.cleared = false;
                if (await this.registerEntry(entry)) {
                    ok++;
                } else {
                    failed++;
                }
                continue;
            }
            const result = await this.client.reindex(entry.workspaceId, full);
            if (result.ok) {
                entry.status = result.data;
                ok++;
            } else if (result.error.error === 'workspace_not_found' && await this.registerEntry(entry)) {
                ok++;
            } else {
                failed++;
                firstError ??= result.error.message;
            }
        }
        this.fireStatus();
        return { ok, failed, firstError };
    }

    /** Deletes every folder's index on the proxy and stops syncing them until reindexed. */
    async removeAll(): Promise<{ removed: number; failed: number; firstError: string | null }> {
        let removed = 0;
        let failed = 0;
        let firstError: string | null = null;
        for (const entry of this.entries.values()) {
            if (entry.workspaceId === null) {
                entry.cleared = true;
                continue;
            }
            const result = await this.client.remove(entry.workspaceId);
            if (result.ok || result.error.error === 'workspace_not_found') {
                entry.workspaceId = null;
                entry.status = null;
                entry.cleared = true;
                removed++;
            } else {
                failed++;
                firstError ??= result.error.message;
            }
        }
        this.fireStatus();
        return { removed, failed, firstError };
    }

    /** Fetches fresh statuses now instead of waiting for the next poll. */
    async refreshNow(): Promise<void> {
        await this.pollOnce();
    }

    private onProxyStatus(status: ProxyStatus, force = false): void {
        const baseUrl = this.client.getBaseUrl();
        const shouldRun = isVatStashEnabled() && status.running;
        if (!shouldRun) {
            this.deactivate();
            return;
        }
        // A new port also means a new proxy process, so treat it as a fresh transition.
        const transition = force || !this.active || baseUrl !== this.lastBaseUrl;
        this.active = true;
        this.lastBaseUrl = baseUrl;
        if (transition) {
            void this.syncFolders().then(() => this.registerAll());
            this.startPolling();
        }
    }

    private deactivate(): void {
        const wasActive = this.active || this.entries.size > 0;
        this.active = false;
        this.lastBaseUrl = null;
        this.stopPolling();
        for (const entry of this.entries.values()) {
            this.disposeEntry(entry);
        }
        this.entries.clear();
        if (wasActive) {
            this.statusEmitter.fire([]);
        }
    }

    /** Adds watchers for new folders and drops those that were closed. */
    private async syncFolders(): Promise<void> {
        if (!this.active) {
            return;
        }
        const folders = (vscode.workspace.workspaceFolders ?? []).filter((f) => f.uri.scheme === 'file');
        const wanted = new Set(folders.map((f) => f.uri.fsPath));
        for (const [key, entry] of this.entries) {
            if (!wanted.has(key)) {
                this.disposeEntry(entry);
                this.entries.delete(key);
            }
        }
        for (const folder of folders) {
            if (!this.entries.has(folder.uri.fsPath)) {
                this.entries.set(folder.uri.fsPath, this.createEntry(folder));
            }
        }
        this.fireStatus();
    }

    private createEntry(folder: vscode.WorkspaceFolder): FolderEntry {
        const entryRef: { current: FolderEntry | null } = { current: null };
        const batcher = new ChangeBatcher(async (changed, deleted) => {
            const entry = entryRef.current;
            if (!entry || entry.workspaceId === null) {
                return; // not registered yet; the registration's own reconcile covers these files
            }
            const result = await this.client.pushChanges(entry.workspaceId, { changed, deleted });
            if (!result.ok && result.error.error === 'workspace_not_found') {
                // The proxy lost its registry (restart, or another window cleared the index).
                if (!entry.cleared) {
                    await this.registerEntry(entry);
                }
            }
        });
        const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(folder, '**/*'));
        const accept = (uri: vscode.Uri): boolean => uri.scheme === 'file' && !isObviouslyIgnored(uri.fsPath);
        const subscriptions = [
            watcher.onDidCreate((uri) => { if (accept(uri)) { batcher.changed(uri.fsPath); } }),
            watcher.onDidChange((uri) => { if (accept(uri)) { batcher.changed(uri.fsPath); } }),
            watcher.onDidDelete((uri) => { if (accept(uri)) { batcher.deleted(uri.fsPath); } })
        ];
        const entry: FolderEntry = { folder, workspaceId: null, status: null, cleared: false, watcher, batcher, subscriptions };
        entryRef.current = entry;
        return entry;
    }

    private disposeEntry(entry: FolderEntry): void {
        entry.subscriptions.forEach((d) => d.dispose());
        entry.watcher.dispose();
        entry.batcher.dispose();
    }

    private async registerAll(): Promise<void> {
        if (!this.active) {
            return;
        }
        await Promise.all([...this.entries.values()].map((entry) => entry.cleared ? Promise.resolve(false) : this.registerEntry(entry)));
        this.fireStatus();
    }

    private async registerEntry(entry: FolderEntry): Promise<boolean> {
        const config = vatstashConfig();
        const result = await this.client.register({
            root: entry.folder.uri.fsPath,
            settings: {
                excludeGlobs: config.get<string[]>('excludeGlobs', []),
                maxFileBytes: Math.round(config.get<number>('maxFileSizeKb', 512) * 1024),
                maxFiles: config.get<number>('maxFiles', 50000)
            },
            client: 'vscode'
        });
        if (result.ok) {
            entry.workspaceId = result.data.workspaceId;
            entry.status = result.data.status;
            return true;
        }
        entry.workspaceId = null;
        entry.status = null;
        return false;
    }

    private startPolling(): void {
        if (this.pollHandle) {
            return;
        }
        this.pollHandle = setInterval(() => void this.pollOnce(), POLL_MS);
    }

    private stopPolling(): void {
        if (this.pollHandle) {
            clearInterval(this.pollHandle);
            this.pollHandle = null;
        }
    }

    private async pollOnce(): Promise<void> {
        if (!this.active || this.polling) {
            return;
        }
        this.polling = true;
        try {
            await Promise.all([...this.entries.values()].map(async (entry) => {
                if (entry.cleared) {
                    return;
                }
                if (entry.workspaceId === null) {
                    await this.registerEntry(entry); // an earlier registration failed; try again
                    return;
                }
                const result = await this.client.status(entry.workspaceId);
                if (result.ok) {
                    entry.status = result.data;
                } else if (result.error.error === 'workspace_not_found') {
                    await this.registerEntry(entry);
                }
                // Other failures keep the last known status rather than blanking the UI.
            }));
            this.fireStatus();
        } finally {
            this.polling = false;
        }
    }

    private fireStatus(): void {
        this.statusEmitter.fire(this.getStatuses());
    }

    dispose(): void {
        this.stopPolling();
        for (const entry of this.entries.values()) {
            this.disposeEntry(entry);
        }
        this.entries.clear();
        this.disposables.forEach((d) => d.dispose());
    }
}
