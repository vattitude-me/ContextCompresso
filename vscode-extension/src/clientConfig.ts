import * as vscode from 'vscode';

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
 * Claude Code reads ANTHROPIC_BASE_URL from the environment, not from VS Code settings —
 * there is no API for an extension to set process-level env vars for external terminals or
 * the Claude Code CLI itself. The most reliable thing this extension can do is set it for
 * VS Code's own integrated terminal profile and tell the user explicitly what changed.
 */
export async function configureClaudeCode(baseUrl: string): Promise<void> {
    const config = vscode.workspace.getConfiguration();
    const envKey = process.platform === 'win32'
        ? 'terminal.integrated.env.windows'
        : process.platform === 'darwin'
            ? 'terminal.integrated.env.osx'
            : 'terminal.integrated.env.linux';

    const existingEnv = config.get<Record<string, string>>(envKey, {});
    await config.update(envKey, {
        ...existingEnv,
        ANTHROPIC_BASE_URL: baseUrl
    }, vscode.ConfigurationTarget.Global);

    const choice = await vscode.window.showInformationMessage(
        `Set ANTHROPIC_BASE_URL=${baseUrl} for new integrated terminals. Existing terminals need to be restarted, and any shell you launch outside VS Code needs 'export ANTHROPIC_BASE_URL=${baseUrl}' manually.`,
        'Copy export command'
    );
    if (choice === 'Copy export command') {
        await vscode.env.clipboard.writeText(`export ANTHROPIC_BASE_URL=${baseUrl}`);
    }
}
