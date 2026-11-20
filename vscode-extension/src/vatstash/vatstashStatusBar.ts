import * as vscode from 'vscode';
import { ProxyManager } from '../proxyManager';
import { VatStashClient } from './vatstashClient';
import { WorkspaceSync, vatstashConfig, isVatStashEnabled } from './workspaceSync';
import { IndexStatus, MetricsSummary } from './types';

const METRICS_REFRESH_MS = 30000;

function formatFiles(n: number): string {
    return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}

/** Second status bar item, next to the proxy one, showing index freshness. */
export class VatStashStatusBar implements vscode.Disposable {
    private readonly item: vscode.StatusBarItem;
    private readonly disposables: vscode.Disposable[] = [];
    private summary: MetricsSummary | null = null;
    private lastSummaryAt = 0;

    constructor(
        private readonly sync: WorkspaceSync,
        private readonly proxyManager: ProxyManager,
        private readonly client: VatStashClient
    ) {
        this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 99);
        this.item.command = 'contextcompresso.vatstash.showMenu';
        this.disposables.push(
            this.item,
            sync.onDidChangeStatus((statuses) => this.render(statuses)),
            proxyManager.onStatusChange(() => this.render(this.sync.getStatuses())),
            vscode.workspace.onDidChangeConfiguration((e) => {
                if (e.affectsConfiguration('contextcompresso.vatstash')) {
                    this.render(this.sync.getStatuses());
                }
            })
        );
        this.render(sync.getStatuses());
    }

    private render(statuses: IndexStatus[]): void {
        const visible = isVatStashEnabled()
            && vatstashConfig().get<boolean>('showStatusBar', true)
            && this.proxyManager.getStatus().running
            && this.sync.hasFolders()
            && statuses.length > 0;
        if (!visible) {
            this.item.hide();
            return;
        }

        const errored = statuses.some((s) => s.state === 'error');
        const building = statuses.filter((s) => s.state === 'building');
        const updating = statuses.some((s) => s.state === 'updating');
        const files = statuses.reduce((sum, s) => sum + s.filesIndexed, 0);

        this.item.backgroundColor = undefined;
        if (errored) {
            this.item.text = '$(warning) VS: index error';
            this.item.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
        } else if (building.length > 0) {
            const done = building.reduce((sum, s) => sum + (s.progress?.done ?? 0), 0);
            const total = building.reduce((sum, s) => sum + (s.progress?.total ?? 0), 0);
            this.item.text = total > 0
                ? `$(sync~spin) VS: indexing ${Math.min(100, Math.round((done / total) * 100))}%`
                : '$(sync~spin) VS: indexing';
        } else if (updating) {
            this.item.text = '$(sync~spin) VS: updating';
        } else {
            this.item.text = `$(search) VS: ${formatFiles(files)} files`;
        }
        this.item.tooltip = this.buildTooltip(statuses);
        this.item.show();
        void this.refreshSummary();
    }

    private async refreshSummary(): Promise<void> {
        if (Date.now() - this.lastSummaryAt < METRICS_REFRESH_MS) {
            return;
        }
        this.lastSummaryAt = Date.now();
        const result = await this.client.metricsSummary(1);
        if (result.ok) {
            this.summary = result.data;
            this.item.tooltip = this.buildTooltip(this.sync.getStatuses());
        }
    }

    private buildTooltip(statuses: IndexStatus[]): vscode.MarkdownString {
        const md = new vscode.MarkdownString();
        md.appendMarkdown('**VatStash code search**\n\n');
        for (const s of statuses) {
            const name = s.root.split(/[\\/]/).filter(Boolean).pop() ?? s.root;
            const progress = s.state === 'building' && s.progress?.total > 0 ? ` ${s.progress.done}/${s.progress.total}` : '';
            md.appendText(`${name}: ${s.state}${progress}, ${s.filesIndexed} files, ${s.chunksIndexed} chunks, ${s.freshness}`);
            md.appendMarkdown('\n\n');
            if (s.truncated) {
                md.appendMarkdown('_File limit reached; some files are not indexed._\n\n');
            }
            if (s.lastError) {
                md.appendText(`Error: ${s.lastError}`);
                md.appendMarkdown('\n\n');
            }
        }
        if (this.summary) {
            md.appendMarkdown(`Today: ${this.summary.calls} search calls, ${this.summary.returnedTokens.toLocaleString()} tokens returned.\n\n`);
        }
        md.appendMarkdown('_Click for VatStash actions._');
        return md;
    }

    dispose(): void {
        this.disposables.forEach((d) => d.dispose());
    }
}
