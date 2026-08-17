import * as vscode from 'vscode';
import { ProxyManager } from './proxyManager';
import { StatsClient } from './statsClient';
import { StatusBarController } from './statusBar';
import { DashboardPanel } from './dashboardPanel';
import { configureCopilot, configureClaudeCode, ConfigureResult } from './clientConfig';

const STATUS_BAR_POLL_MS = 5000;

export function activate(context: vscode.ExtensionContext): void {
    const proxyManager = new ProxyManager(context);
    const statsClient = new StatsClient(() => proxyManager.getBaseUrl());
    const statusBar = new StatusBarController();

    context.subscriptions.push(proxyManager, statusBar);

    proxyManager.onStatusChange((status) => statusBar.updateProxyStatus(status), null, context.subscriptions);

    // Lifecycle transitions arrive via onStatusChange; this poll exists only to refresh the
    // live numbers on an already-running proxy.
    const pollHandle = setInterval(async () => {
        if (!proxyManager.getStatus().running) {
            return;
        }
        const live = await statsClient.fetchLive();
        statusBar.updateStats(live);
    }, STATUS_BAR_POLL_MS);
    context.subscriptions.push({ dispose: () => clearInterval(pollHandle) });

    context.subscriptions.push(
        vscode.commands.registerCommand('contextcompresso.showPanel', () => {
            const extensionVersion = context.extension.packageJSON.version as string;
            DashboardPanel.show(context, statsClient, proxyManager, extensionVersion);
        }),
        vscode.commands.registerCommand('contextcompresso.start', async () => {
            await proxyManager.start();
            const status = proxyManager.getStatus();
            if (status.running) {
                vscode.window.showInformationMessage(`ContextCompresso proxy running on ${proxyManager.getBaseUrl()}.`);
            } else {
                void showFailure(proxyManager, status.lastError ?? 'The proxy did not start.');
            }
        }),
        vscode.commands.registerCommand('contextcompresso.stop', async () => {
            await proxyManager.stop();
            vscode.window.showInformationMessage('ContextCompresso proxy stopped.');
        }),
        vscode.commands.registerCommand('contextcompresso.restart', async () => {
            await proxyManager.restart();
            const status = proxyManager.getStatus();
            if (status.running) {
                vscode.window.showInformationMessage(`ContextCompresso proxy restarted on ${proxyManager.getBaseUrl()}.`);
            } else {
                void showFailure(proxyManager, status.lastError ?? 'The proxy did not come back up.');
            }
        }),
        vscode.commands.registerCommand('contextcompresso.openLogs', () => {
            proxyManager.showLogs();
        }),
        vscode.commands.registerCommand('contextcompresso.configureCopilot', async () => {
            const baseUrl = proxyManager.getBaseUrl();
            if (!baseUrl) {
                vscode.window.showErrorMessage('Start the ContextCompresso proxy first — there is no address to point Copilot at yet.');
                return;
            }
            await reportConfigure(await configureCopilot(baseUrl));
        }),
        vscode.commands.registerCommand('contextcompresso.configureClaudeCode', async () => {
            const baseUrl = proxyManager.getBaseUrl();
            if (!baseUrl) {
                vscode.window.showErrorMessage('Start the ContextCompresso proxy first — there is no address to point Claude Code at yet.');
                return;
            }
            await reportConfigure(await configureClaudeCode(baseUrl));
        })
    );

    const config = vscode.workspace.getConfiguration('contextcompresso');
    if (config.get<boolean>('autoStart', true)) {
        void proxyManager.start().then(async () => {
            const status = proxyManager.getStatus();
            if (status.running) {
                await promptForClientSetup(context, proxyManager);
                return;
            }
            // Silent failure on activation is the worst version of this: the status bar shows
            // an error the user never opted into and has no path out of.
            await showFailure(proxyManager, `ContextCompresso could not start: ${status.lastError ?? 'unknown error.'}`);
        });
    }
}

/** Surfaces a configure result from the Command Palette, offering the reload when one is needed. */
async function reportConfigure(result: ConfigureResult): Promise<void> {
    if (!result.ok) {
        vscode.window.showErrorMessage(result.message);
        return;
    }
    if (!result.needsReload) {
        vscode.window.showInformationMessage(result.message);
        return;
    }
    const choice = await vscode.window.showInformationMessage(result.message, 'Reload Window');
    if (choice === 'Reload Window') {
        await vscode.commands.executeCommand('workbench.action.reloadWindow');
    }
}

/**
 * A bare error toast leaves the user with nothing to do about it. Every failure here is
 * recoverable through either the logs or a setting (missing Java, wrong jar path), so offer
 * both routes rather than just stating the problem.
 */
async function showFailure(proxyManager: ProxyManager, message: string): Promise<void> {
    const choice = await vscode.window.showErrorMessage(message, 'Open Logs', 'Open Settings');
    if (choice === 'Open Logs') {
        proxyManager.showLogs();
    } else if (choice === 'Open Settings') {
        await vscode.commands.executeCommand('workbench.action.openSettings', 'contextcompresso');
    }
}

/**
 * Installing the extension only starts the local proxy - nothing routes traffic to it until
 * the user runs one of the "Point X at Proxy" commands. That requirement is easy to miss since
 * it lives in the Command Palette, so prompt for it once, right after the first successful
 * start, instead of assuming it'll be discovered.
 */
async function promptForClientSetup(context: vscode.ExtensionContext, proxyManager: ProxyManager): Promise<void> {
    const alreadyPrompted = context.globalState.get<boolean>('contextcompresso.didPromptClientSetup', false);
    if (alreadyPrompted || !proxyManager.getStatus().running) {
        return;
    }
    await context.globalState.update('contextcompresso.didPromptClientSetup', true);

    const choice = await vscode.window.showInformationMessage(
        'ContextCompresso is running, but nothing is routed through it yet. Point a client at the proxy to start compressing requests.',
        'Point Claude Code at Proxy',
        'Point GitHub Copilot at Proxy',
        'Not now'
    );
    const baseUrl = proxyManager.getBaseUrl();
    if (choice === 'Point Claude Code at Proxy' && baseUrl) {
        await reportConfigure(await configureClaudeCode(baseUrl));
    } else if (choice === 'Point GitHub Copilot at Proxy' && baseUrl) {
        await reportConfigure(await configureCopilot(baseUrl));
    }
}

export function deactivate(): Promise<void> | void {
    // proxyManager is disposed via context.subscriptions, which kills the child process
}
