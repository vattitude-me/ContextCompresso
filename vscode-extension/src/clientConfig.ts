import * as vscode from 'vscode';

/**
 * Lets the dashboard tell "running but nothing routed through it yet" apart from "actually
 * receiving traffic for some other reason" by checking whether either client's proxy setting
 * currently points at the given base URL — read live so it stays correct if the user edits
 * settings.json by hand instead of using the command.
 */
export function isClaudeCodeConfigured(baseUrl: string): boolean {
    const vars = vscode.workspace.getConfiguration('claudeCode')
        .get<{ name: string; value: string }[]>('environmentVariables', []);
    return vars.some((v) => v.name === 'ANTHROPIC_BASE_URL' && v.value === baseUrl);
}

export function isCopilotConfigured(baseUrl: string): boolean {
    const advanced = vscode.workspace.getConfiguration()
        .get<Record<string, unknown>>('github.copilot.advanced', {});
    return advanced['debug.overrideProxyUrl'] === baseUrl;
}

/**
 * Points GitHub Copilot's chat client at the local proxy via VS Code's own settings.json.
 * This is an undocumented `debug.*` override — Copilot may change or remove it in a future
 * release without notice, so this is offered as an explicit user action rather than
 * something the extension does silently on activation.
 */
export async function configureCopilot(baseUrl: string): Promise<void> {
    const config = vscode.workspace.getConfiguration();
    const advanced = config.get<Record<string, unknown>>('github.copilot.advanced', {});
    await config.update('github.copilot.advanced', {
        ...advanced,
        'debug.overrideProxyUrl': baseUrl
    }, vscode.ConfigurationTarget.Global);

    vscode.window.showInformationMessage(
        `GitHub Copilot is now pointed at ContextCompresso (${baseUrl}). Reload Copilot Chat or restart VS Code for it to take effect.`
    );
}

/**
 * The Claude Code VS Code extension (anthropic.claude-code) spawns its own native `claude`
 * binary directly from the extension host process — not from a VS Code integrated terminal —
 * so `terminal.integrated.env.*` settings never reach it. It builds that child's environment
 * from `{...process.env}` overlaid with its own `claudeCode.environmentVariables` setting, so
 * that's the one place we can reliably inject ANTHROPIC_BASE_URL for this entrypoint. This
 * takes effect on the next `claude` invocation with no VS Code restart required.
 *
 * Written to the workspace (not Global) so pointing Claude Code at this proxy is opt-in per
 * project — a Global write would silently route every other VS Code window through this
 * proxy too, so any outage here would take down Claude Code everywhere, not just this repo.
 * Requires an open workspace folder; without one there's no workspace settings.json to write.
 */
export async function configureClaudeCode(baseUrl: string): Promise<void> {
    if (!vscode.workspace.workspaceFolders?.length) {
        vscode.window.showErrorMessage(
            'Open a workspace folder first — Claude Code proxy settings are written per-project so other windows are unaffected.'
        );
        return;
    }

    const config = vscode.workspace.getConfiguration('claudeCode');
    const inspected = config.inspect<{ name: string; value: string }[]>('environmentVariables');
    const existingWorkspaceVars = inspected?.workspaceValue ?? [];
    const withoutOldBaseUrl = existingWorkspaceVars.filter((v) => v.name !== 'ANTHROPIC_BASE_URL');
    await config.update('environmentVariables', [
        ...withoutOldBaseUrl,
        { name: 'ANTHROPIC_BASE_URL', value: baseUrl }
    ], vscode.ConfigurationTarget.Workspace);

    if (inspected?.globalValue?.some((v) => v.name === 'ANTHROPIC_BASE_URL')) {
        const withoutGlobalBaseUrl = inspected.globalValue.filter((v) => v.name !== 'ANTHROPIC_BASE_URL');
        await config.update('environmentVariables', withoutGlobalBaseUrl, vscode.ConfigurationTarget.Global);
    }

    const choice = await vscode.window.showInformationMessage(
        `Set claudeCode.environmentVariables so Claude Code (VS Code extension) routes through ContextCompresso (${baseUrl}). Takes effect on the next Claude Code request in this window. For the Claude Code CLI run outside VS Code, export ANTHROPIC_BASE_URL=${baseUrl} in your shell instead.`,
        'Copy export command (CLI)'
    );
    if (choice === 'Copy export command (CLI)') {
        await vscode.env.clipboard.writeText(`export ANTHROPIC_BASE_URL=${baseUrl}`);
    }
}
