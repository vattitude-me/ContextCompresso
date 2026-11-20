import * as vscode from 'vscode';
import { VatStashClient } from './vatstashClient';
import { WorkspaceSync, vatstashConfig, isVatStashEnabled } from './workspaceSync';
import { SearchResponse } from './types';

export interface SearchToolInput {
    query: string;
    pathPrefix?: string;
    languages?: string[];
    maxResults?: number;
}

export const TOOL_NAME = 'vatstash_search';

export function unavailableText(reason: string): string {
    return `[vatstash v1] VatStash is unavailable (${reason}). Use your normal search tools.`;
}

export function clampMaxResults(requested: number | undefined, fallback: number): number {
    const value = typeof requested === 'number' && Number.isFinite(requested) ? requested : fallback;
    return Math.min(20, Math.max(1, Math.round(value)));
}

/**
 * Runs one search against every indexed folder. Shared by the LM tool and the chat participant
 * so both read the same settings and report failures the same way. Never throws.
 */
export async function runSearch(
    client: VatStashClient,
    sync: WorkspaceSync,
    input: SearchToolInput,
    caller: 'copilot-tool' | 'chat-participant' | 'command'
): Promise<{ ok: true; response: SearchResponse } | { ok: false; reason: string }> {
    if (!isVatStashEnabled()) {
        return { ok: false, reason: 'disabled in settings' };
    }
    const workspaceIds = sync.getWorkspaceIds();
    if (workspaceIds.length === 0) {
        return { ok: false, reason: 'no workspace folder is indexed yet' };
    }
    const query = typeof input?.query === 'string' ? input.query.trim() : '';
    if (!query) {
        return { ok: false, reason: 'the query is empty' };
    }
    const config = vatstashConfig();
    const result = await client.search({
        query,
        workspaceIds,
        pathPrefix: input.pathPrefix?.trim() || undefined,
        languages: input.languages && input.languages.length > 0 ? input.languages : undefined,
        maxResults: clampMaxResults(input.maxResults, config.get<number>('maxResults', 8)),
        maxTokens: config.get<number>('maxResultTokens', 4000),
        client: caller
    });
    if (!result.ok) {
        return { ok: false, reason: result.error.message || result.error.error };
    }
    return { ok: true, response: result.data };
}

/**
 * Registers the `vatstash_search` language model tool. LM tools need VS Code 1.95+, while the
 * extension supports 1.85+, so the API is feature-detected and older hosts simply skip it.
 */
export function registerSearchTool(context: vscode.ExtensionContext, client: VatStashClient, sync: WorkspaceSync): void {
    const lm = (vscode as { lm?: Partial<typeof vscode.lm> }).lm;
    if (typeof lm?.registerTool !== 'function') {
        return;
    }
    try {
        const tool = vscode.lm.registerTool<SearchToolInput>(TOOL_NAME, {
            prepareInvocation(options) {
                return { invocationMessage: `Searching code index for "${options.input?.query ?? ''}"` };
            },
            async invoke(options) {
                let text: string;
                try {
                    const outcome = await runSearch(client, sync, options.input, 'copilot-tool');
                    text = outcome.ok ? outcome.response.text : unavailableText(outcome.reason);
                } catch (err) {
                    text = unavailableText(err instanceof Error ? err.message : 'unexpected error');
                }
                return new vscode.LanguageModelToolResult([new vscode.LanguageModelTextPart(text)]);
            }
        });
        context.subscriptions.push(tool);
    } catch {
        // A host that exposes registerTool but rejects the contribution must not break activation.
    }
}
