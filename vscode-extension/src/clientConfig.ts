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
 * The Claude Code VS Code extension (anthropic.claude-code) spawns its own native `claude`
 * binary directly from the extension host process — not from a VS Code integrated terminal —
 * so `terminal.integrated.env.*` settings never reach it. It builds that child's environment
 * from `{...process.env}` overlaid with its own `claudeCode.environmentVariables` setting, so
 * that's the one place we can reliably inject ANTHROPIC_BASE_URL for this entrypoint. This
 * takes effect on the next `claude` invocation with no VS Code restart required.
 */
export async function configureClaudeCode(baseUrl: string): Promise<void> {
    const config = vscode.workspace.getConfiguration('claudeCode');
    const existingVars = config.get<{ name: string; value: string }[]>('environmentVariables', []);
    const withoutOldBaseUrl = existingVars.filter((v) => v.name !== 'ANTHROPIC_BASE_URL');
    await config.update('environmentVariables', [
        ...withoutOldBaseUrl,
        { name: 'ANTHROPIC_BASE_URL', value: baseUrl }
    ], vscode.ConfigurationTarget.Global);

    const choice = await vscode.window.showInformationMessage(
        `Set claudeCode.environmentVariables so Claude Code (VS Code extension) routes through ContextCompresso (${baseUrl}). Takes effect on the next Claude Code request in this window. For the Claude Code CLI run outside VS Code, export ANTHROPIC_BASE_URL=${baseUrl} in your shell instead.`,
        'Copy export command (CLI)'
    );
    if (choice === 'Copy export command (CLI)') {
        await vscode.env.clipboard.writeText(`export ANTHROPIC_BASE_URL=${baseUrl}`);
    }
}
