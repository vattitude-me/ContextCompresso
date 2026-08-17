import * as vscode from 'vscode';
import { LiveStats } from './statsClient';
import { ProxyStatus } from './proxyManager';

const CACHE_HIT_GOOD = 0.85;
const CACHE_HIT_WARN = 0.6;

function formatCompact(value: number): string {
    if (value >= 1_000_000) {
        return `${(value / 1_000_000).toFixed(1)}m`;
    }
    if (value >= 1_000) {
        return `${(value / 1_000).toFixed(0)}k`;
    }
    return String(Math.round(value));
}

function trendArrow(delta: number): string {
    if (delta > 0.02) {
        return `▲${Math.round(delta * 100)}%`;
    }
    if (delta < -0.02) {
        return `▼${Math.round(Math.abs(delta) * 100)}%`;
    }
    return '';
}

/**
 * The primary always-visible surface. Most of the team will never open the panel mid-task,
 * so the status bar has to carry the whole signal on its own: cache hit rate (the number
 * that predicts cost swings) and effective input tokens (the number that maps to the credit
 * bar), plus a same-turn trend arrow that catches a runaway context before it drains a
 * session's budget.
 */
export class StatusBarController implements vscode.Disposable {
    private readonly item: vscode.StatusBarItem;
    private lastState: ProxyStatus['state'] = 'stopped';

    constructor() {
        this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        this.item.command = 'contextcompresso.showPanel';
        this.item.show();
        this.renderLifecycle({ running: false, state: 'stopped', port: null, pid: null, lastError: null, busy: false });
    }

    updateProxyStatus(status: ProxyStatus): void {
        this.lastState = status.state;
        if (!status.running) {
            this.renderLifecycle(status);
        }
    }

    /**
     * Non-running states are not interchangeable: "starting" is normal progress and must not
     * wear the error background that "crashed" does, or every launch looks like a failure.
     */
    private renderLifecycle(status: ProxyStatus): void {
        const errorBg = new vscode.ThemeColor('statusBarItem.warningBackground');
        switch (status.state) {
            case 'starting':
                this.item.text = '$(loading~spin) CC: starting…';
                this.item.tooltip = 'ContextCompresso proxy is starting up.';
                this.item.backgroundColor = undefined;
                return;
            case 'stopping':
                this.item.text = '$(loading~spin) CC: stopping…';
                this.item.tooltip = 'ContextCompresso proxy is shutting down.';
                this.item.backgroundColor = undefined;
                return;
            case 'stopped':
                this.item.text = '$(debug-start) CC: stopped';
                this.item.tooltip = 'ContextCompresso proxy is stopped. Click to open the dashboard and start it.';
                this.item.backgroundColor = undefined;
                return;
            default:
                this.item.text = '$(warning) CC: proxy error';
                this.item.tooltip = status.lastError ?? 'The ContextCompresso proxy failed. Click to open the dashboard.';
                this.item.backgroundColor = errorBg;
        }
    }

    updateStats(stats: LiveStats | null): void {
        if (this.lastState !== 'running' && this.lastState !== 'adopted') {
            return; // a stale stats poll must not overwrite a starting/stopped/error label
        }
        if (!stats || stats.turns === 0) {
            this.item.text = '$(check) CC: ready';
            this.item.tooltip = 'ContextCompresso proxy is running. No requests captured yet — click for the dashboard.';
            this.item.backgroundColor = undefined;
            return;
        }

        const hitPct = Math.round(stats.cacheHitRate * 100);
        const effective = formatCompact(stats.effectiveInputTokens);
        const trend = stats.lastTurn ? trendArrow(stats.lastTurn.deltaVsPrev) : '';

        const icon = stats.cacheHitRate >= CACHE_HIT_GOOD ? '$(check)'
            : stats.cacheHitRate >= CACHE_HIT_WARN ? '$(warning)'
            : '$(flame)';

        this.item.text = `${icon} ${hitPct}% cached · ${effective} eff${trend ? ' · ' + trend : ''}`;
        this.item.tooltip = this.buildTooltip(stats);
        this.item.backgroundColor = stats.cacheHitRate < CACHE_HIT_WARN
            ? new vscode.ThemeColor('statusBarItem.warningBackground')
            : undefined;
    }

    private buildTooltip(stats: LiveStats): vscode.MarkdownString {
        const md = new vscode.MarkdownString();
        md.appendMarkdown(`**ContextCompresso** — session: ${stats.turns} turns\n\n`);
        md.appendMarkdown(`| | tokens |\n|---|---|\n`);
        md.appendMarkdown(`| Cache read | ${stats.tokens.cacheRead.toLocaleString()} |\n`);
        md.appendMarkdown(`| Fresh input | ${stats.tokens.input.toLocaleString()} |\n`);
        md.appendMarkdown(`| Cache write | ${stats.tokens.cacheWrite.toLocaleString()} |\n`);
        md.appendMarkdown(`| Output | ${stats.tokens.output.toLocaleString()} |\n\n`);
        md.appendMarkdown(`**Effective input:** ${stats.effectiveInputTokens.toLocaleString()} tokens\n\n`);
        md.appendMarkdown(`Compression: ${Math.round(stats.compression.ratio * 100)}% of request chars kept\n\n`);
        md.appendMarkdown(`_Click for the full dashboard._`);
        return md;
    }

    dispose(): void {
        this.item.dispose();
    }
}
