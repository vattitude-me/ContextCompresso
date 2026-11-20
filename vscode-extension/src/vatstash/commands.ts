import * as vscode from 'vscode';
import { ProxyManager } from '../proxyManager';
import { VatStashClient } from './vatstashClient';
import { WorkspaceSync } from './workspaceSync';
import { runSearch } from './searchTool';
import { SearchHit } from './types';

const MENU: { label: string; command: string }[] = [
    { label: 'Search code index…', command: 'contextcompresso.vatstash.search' },
    { label: 'Reindex workspace', command: 'contextcompresso.vatstash.reindex' },
    { label: 'Rebuild index from scratch', command: 'contextcompresso.vatstash.rebuild' },
    { label: 'Clear index', command: 'contextcompresso.vatstash.clearIndex' },
    { label: 'Export metrics…', command: 'contextcompresso.vatstash.exportMetrics' },
    { label: 'Copy Claude Code MCP command', command: 'contextcompresso.vatstash.copyClaudeMcpCommand' },
    { label: 'Open dashboard', command: 'contextcompresso.showPanel' }
];

export function registerVatStashCommands(
    context: vscode.ExtensionContext,
    client: VatStashClient,
    sync: WorkspaceSync,
    proxyManager: ProxyManager
): void {
    const reg = (id: string, fn: (...args: unknown[]) => unknown) =>
        context.subscriptions.push(vscode.commands.registerCommand(id, fn));

    reg('contextcompresso.vatstash.reindex', async () => {
        if (!requireFolders(sync)) {
            return;
        }
        const r = await sync.reindexAll(false);
        report(r.failed === 0, 'VatStash is reconciling the index.', r.firstError);
    });

    reg('contextcompresso.vatstash.rebuild', async () => {
        if (!requireFolders(sync)) {
            return;
        }
        const choice = await vscode.window.showWarningMessage(
            'Rebuild the VatStash index from scratch?',
            { modal: true, detail: 'The current index is dropped and every file is read again. Search results may be incomplete until it finishes.' },
            'Rebuild'
        );
        if (choice !== 'Rebuild') {
            return;
        }
        const r = await sync.reindexAll(true);
        report(r.failed === 0, 'VatStash is rebuilding the index.', r.firstError);
    });

    reg('contextcompresso.vatstash.clearIndex', async () => {
        if (!requireFolders(sync)) {
            return;
        }
        const choice = await vscode.window.showWarningMessage(
            'Delete the VatStash index for this workspace?',
            { modal: true, detail: 'This removes the indexed copy of your source from disk. Run "VatStash Reindex Workspace" to build it again.' },
            'Delete index'
        );
        if (choice !== 'Delete index') {
            return;
        }
        const r = await sync.removeAll();
        report(r.failed === 0, 'VatStash index deleted.', r.firstError);
    });

    reg('contextcompresso.vatstash.search', async () => {
        const query = await vscode.window.showInputBox({
            prompt: 'Search the local code index',
            placeHolder: 'Keywords and identifiers, e.g. payment retry backoff'
        });
        if (!query?.trim()) {
            return;
        }
        const outcome = await runSearch(client, sync, { query }, 'command');
        if (!outcome.ok) {
            vscode.window.showWarningMessage(`VatStash search failed: ${outcome.reason}.`);
            return;
        }
        if (outcome.response.results.length === 0) {
            vscode.window.showInformationMessage('VatStash found no matches. Try identifiers such as class, method or config key names.');
            return;
        }
        const picked = await vscode.window.showQuickPick(
            outcome.response.results.map((hit) => ({
                label: `${hit.path}:${hit.startLine}-${hit.endLine}`,
                description: hit.symbol ?? hit.kind,
                detail: firstLine(hit),
                hit
            })),
            { placeHolder: `${outcome.response.results.length} matches (${outcome.response.freshness} index)`, matchOnDescription: true }
        );
        if (picked) {
            await vscode.window.showTextDocument(vscode.Uri.file(picked.hit.absolutePath), {
                selection: new vscode.Range(picked.hit.startLine - 1, 0, picked.hit.endLine, 0)
            });
        }
    });

    reg('contextcompresso.vatstash.exportMetrics', async () => {
        const format = await vscode.window.showQuickPick(
            [{ label: 'CSV', value: 'csv' as const }, { label: 'Markdown', value: 'md' as const }],
            { placeHolder: 'Export format' }
        );
        if (!format) {
            return;
        }
        const days = await vscode.window.showQuickPick(
            [1, 7, 30, 90].map((d) => ({ label: d === 1 ? 'Last day' : `Last ${d} days`, value: d })),
            { placeHolder: 'Period to export' }
        );
        if (!days) {
            return;
        }
        const body = await client.exportMetrics(format.value, days.value);
        if (body === null) {
            vscode.window.showErrorMessage('VatStash could not export metrics. Is the proxy running?');
            return;
        }
        const stamp = new Date().toISOString().slice(0, 10);
        const target = await vscode.window.showSaveDialog({
            defaultUri: vscode.Uri.file(`vatstash-metrics-${stamp}.${format.value === 'csv' ? 'csv' : 'md'}`),
            filters: format.value === 'csv' ? { CSV: ['csv'] } : { Markdown: ['md'] }
        });
        if (!target) {
            return;
        }
        await vscode.workspace.fs.writeFile(target, Buffer.from(body, 'utf8'));
        vscode.window.showInformationMessage(`VatStash metrics saved to ${target.fsPath}.`);
    });

    reg('contextcompresso.vatstash.copyClaudeMcpCommand', async () => {
        const baseUrl = proxyManager.getBaseUrl();
        if (!baseUrl) {
            vscode.window.showErrorMessage('Start the ContextCompresso proxy first. There is no address to give Claude Code yet.');
            return;
        }
        await vscode.env.clipboard.writeText(`claude mcp add --transport http vatstash ${baseUrl}/mcp`);
        vscode.window.showInformationMessage(
            'Copied the claude mcp add command. Run it in a terminal. The port changes if 8137 is taken, so rerun it if the proxy moves.'
        );
    });

    reg('contextcompresso.vatstash.showMenu', async () => {
        const picked = await vscode.window.showQuickPick(MENU, { placeHolder: 'VatStash code search' });
        if (picked) {
            await vscode.commands.executeCommand(picked.command);
        }
    });
}

function requireFolders(sync: WorkspaceSync): boolean {
    if (!sync.hasFolders()) {
        vscode.window.showWarningMessage('VatStash has no indexed workspace folder. Open a folder and make sure the proxy is running and VatStash is enabled.');
        return false;
    }
    return true;
}

function report(ok: boolean, success: string, error: string | null): void {
    if (ok) {
        vscode.window.showInformationMessage(success);
    } else {
        vscode.window.showErrorMessage(`VatStash: ${error ?? 'the request failed.'}`);
    }
}

function firstLine(hit: SearchHit): string {
    return hit.content.split('\n').find((l) => l.trim().length > 0)?.trim().slice(0, 120) ?? '';
}
