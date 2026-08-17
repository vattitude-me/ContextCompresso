import * as vscode from 'vscode';

/**
 * These functions used to end by awaiting showInformationMessage, which does not resolve
 * until the user dismisses the toast — so any caller awaiting them hung until then, and the
 * dashboard's own confirmation never ran. They now just report what happened and let the
 * caller decide how to surface it.
 */
export interface ConfigureResult {
    ok: boolean;
    message: string;
    needsReload?: boolean;
}

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

/**
 * True when a base URL is set but points somewhere other than this proxy — usually a stale
 * override from a previous run on a different port. Worth calling out: it looks identical to
 * "not connected" in the UI, but the cause and the fix are different.
 */
export function claudeCodeOverrideTarget(): string | null {
    const vars = vscode.workspace.getConfiguration('claudeCode')
        .get<{ name: string; value: string }[]>('environmentVariables', []);
    return vars.find((v) => v.name === 'ANTHROPIC_BASE_URL')?.value ?? null;
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
export async function configureCopilot(baseUrl: string): Promise<ConfigureResult> {
    if (!vscode.extensions.getExtension('GitHub.copilot')) {
        return {
            ok: false,
            message: 'GitHub Copilot extension is not installed or enabled. Install it from the Extensions view, then try again.'
        };
    }

    const config = vscode.workspace.getConfiguration();
    const advanced = config.get<Record<string, unknown>>('github.copilot.advanced', {});
    try {
        await config.update('github.copilot.advanced', {
            ...advanced,
            'debug.overrideProxyUrl': baseUrl
        }, vscode.ConfigurationTarget.Global);
    } catch (err) {
        return { ok: false, message: `Could not write Copilot settings: ${(err as Error).message}` };
    }

    return {
        ok: true,
        message: `GitHub Copilot now points at ${baseUrl}. Reload the window for it to take effect.`,
        needsReload: true
    };
}

/**
 * The Claude Code VS Code extension (anthropic.claude-code) spawns its own native `claude`
 * binary directly from the extension host process — not from a VS Code integrated terminal —
 * so `terminal.integrated.env.*` settings never reach it. It builds that child's environment
 * from `{...process.env}` overlaid with its own `claudeCode.environmentVariables` setting, so
 * that's the one place we can reliably inject ANTHROPIC_BASE_URL for this entrypoint. This
 * takes effect on the next `claude` invocation with no VS Code restart required.
 *
 * This *must* be a Global (User settings) write: claudeCode.environmentVariables is declared
 * with `"scope": "machine"`, and VS Code rejects a workspace write for machine-scoped settings
 * outright ("This setting can be written only into User settings"). So the per-project
 * isolation we'd prefer isn't available — pointing Claude Code here affects every VS Code
 * window on this machine, and the caller is expected to say so before writing.
 */
export async function configureClaudeCode(baseUrl: string): Promise<ConfigureResult> {
    const config = vscode.workspace.getConfiguration('claudeCode');
    const inspected = config.inspect<{ name: string; value: string }[]>('environmentVariables');
    const existingGlobalVars = inspected?.globalValue ?? [];
    const withoutOldBaseUrl = existingGlobalVars.filter((v) => v.name !== 'ANTHROPIC_BASE_URL');
    try {
        await config.update('environmentVariables', [
            ...withoutOldBaseUrl,
            { name: 'ANTHROPIC_BASE_URL', value: baseUrl }
        ], vscode.ConfigurationTarget.Global);
    } catch (err) {
        return { ok: false, message: `Could not write Claude Code settings: ${(err as Error).message}` };
    }

    return {
        ok: true,
        message: `Claude Code now points at ${baseUrl}. Takes effect on its next request.`,
        // The VS Code extension picks this up without a reload; only the standalone CLI needs
        // the shell export, which the panel offers separately.
        needsReload: false
    };
}

/**
 * Removes the ANTHROPIC_BASE_URL override so Claude Code talks to Anthropic directly again.
 * Because the write above is machine-wide, leaving a stale override behind after the proxy
 * stops would break Claude Code in every window — so disconnecting has to be reachable.
 */
export async function disconnectClaudeCode(): Promise<ConfigureResult> {
    const config = vscode.workspace.getConfiguration('claudeCode');
    const inspected = config.inspect<{ name: string; value: string }[]>('environmentVariables');
    const remaining = (inspected?.globalValue ?? []).filter((v) => v.name !== 'ANTHROPIC_BASE_URL');
    try {
        await config.update('environmentVariables', remaining, vscode.ConfigurationTarget.Global);
    } catch (err) {
        return { ok: false, message: `Could not clear Claude Code settings: ${(err as Error).message}` };
    }
    return {
        ok: true,
        message: 'Claude Code now talks to Anthropic directly again. Already-running Claude Code sessions ' +
            'inherited the old setting at launch and need to be restarted to pick this up.'
    };
}

/** Mirror of the above for Copilot, so both connections can be undone from the panel. */
export async function disconnectCopilot(): Promise<ConfigureResult> {
    const config = vscode.workspace.getConfiguration();
    const advanced = { ...config.get<Record<string, unknown>>('github.copilot.advanced', {}) };
    delete advanced['debug.overrideProxyUrl'];
    try {
        await config.update('github.copilot.advanced', advanced, vscode.ConfigurationTarget.Global);
    } catch (err) {
        return { ok: false, message: `Could not clear Copilot settings: ${(err as Error).message}` };
    }
    return { ok: true, message: 'Copilot now talks to GitHub directly again. Reload the window for it to take effect.', needsReload: true };
}
