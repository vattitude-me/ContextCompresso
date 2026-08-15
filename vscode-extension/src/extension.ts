import * as vscode from 'vscode';
import { ProxyManager } from './proxyManager';
import { StatsClient } from './statsClient';
import { StatusBarController } from './statusBar';
import { DashboardPanel } from './dashboardPanel';
import { configureCopilot, configureClaudeCode } from './clientConfig';

const STATUS_BAR_POLL_MS = 5000;

export function activate(context: vscode.ExtensionContext): void {
    const proxyManager = new ProxyManager(context);
    const statsClient = new StatsClient(() => proxyManager.getBaseUrl());
    const statusBar = new StatusBarController();

    context.subscriptions.push(proxyManager, statusBar);

    proxyManager.onStatusChange((status) => statusBar.updateProxyStatus(status), null, context.subscriptions);

    const pollHandle = setInterval(async () => {
        const status = proxyManager.getStatus();
        if (!status.running) {
            statusBar.updateProxyStatus(status);
            return;
        }
        const live = await statsClient.fetchLive();
        statusBar.updateStats(live);
    }, STATUS_BAR_POLL_MS);
    context.subscriptions.push({ dispose: () => clearInterval(pollHandle) });

    context.subscriptions.push(
        vscode.commands.registerCommand('contextcompresso.showPanel', () => {
            DashboardPanel.show(context, statsClient, proxyManager);
        }),
        vscode.commands.registerCommand('contextcompresso.restart', async () => {
            await proxyManager.restart();
            vscode.window.showInformationMessage('ContextCompresso proxy restarted.');
        }),
        vscode.commands.registerCommand('contextcompresso.openLogs', () => {
            proxyManager.showLogs();
        }),
        vscode.commands.registerCommand('contextcompresso.configureCopilot', async () => {
            const baseUrl = proxyManager.getBaseUrl();
            if (!baseUrl) {
                vscode.window.showErrorMessage('ContextCompresso proxy is not running yet.');
                return;
            }
            await configureCopilot(baseUrl);
        }),
        vscode.commands.registerCommand('contextcompresso.configureClaudeCode', async () => {
            const baseUrl = proxyManager.getBaseUrl();
            if (!baseUrl) {
                vscode.window.showErrorMessage('ContextCompresso proxy is not running yet.');
                return;
            }
            await configureClaudeCode(baseUrl);
        })
    );

    const config = vscode.workspace.getConfiguration('contextcompresso');
    if (config.get<boolean>('autoStart', true)) {
        void proxyManager.start();
    }
}

export function deactivate(): Promise<void> | void {
    // proxyManager is disposed via context.subscriptions, which kills the child process
}
