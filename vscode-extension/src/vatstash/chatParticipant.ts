import * as vscode from 'vscode';
import { VatStashClient } from './vatstashClient';
import { WorkspaceSync } from './workspaceSync';
import { runSearch } from './searchTool';
import { SearchHit } from './types';

export const PARTICIPANT_ID = 'contextcompresso.vatstash';

const INSTRUCTIONS =
    'You are answering a question about the user\'s workspace. Below are code snippets retrieved from a local '
    + 'keyword index, each headed by its file path and line range. Answer using these snippets, cite file paths '
    + 'and line ranges, and say so plainly if they do not contain enough to answer.\n\n';

/** A fence longer than any backtick run inside the code, so snippets cannot close it early. */
export function fenceFor(content: string): string {
    const longest = (content.match(/`+/g) ?? []).reduce((max, run) => Math.max(max, run.length), 0);
    return '`'.repeat(Math.max(3, longest + 1));
}

export function renderHits(hits: SearchHit[]): string {
    const list = hits.map((h) => `- \`${h.path}:${h.startLine}-${h.endLine}\`${h.symbol ? ` — ${h.symbol}` : ''}`).join('\n');
    const snippets = hits.map((h) => {
        const fence = fenceFor(h.content);
        return `**${h.path}:${h.startLine}-${h.endLine}**\n\n${fence}${h.language}\n${h.content}\n${fence}`;
    }).join('\n\n');
    return `${list}\n\n${snippets}\n`;
}

/**
 * `@vatstash`: retrieves snippets from the local index, then answers with whichever model the
 * user picked in the chat. Chat participants need VS Code 1.90+, so the API is feature-detected.
 */
export function registerChatParticipant(context: vscode.ExtensionContext, client: VatStashClient, sync: WorkspaceSync): void {
    const chatApi = (vscode as { chat?: Partial<typeof vscode.chat> }).chat;
    if (typeof chatApi?.createChatParticipant !== 'function') {
        return;
    }

    const handler: vscode.ChatRequestHandler = async (request, _chatContext, stream, token) => {
        try {
            stream.progress('Searching the local code index…');
            const outcome = await runSearch(client, sync, { query: request.prompt }, 'chat-participant');
            if (!outcome.ok) {
                stream.markdown(`VatStash is unavailable: ${outcome.reason}.`);
                return {};
            }
            const { response } = outcome;
            for (const hit of response.results) {
                stream.reference(new vscode.Location(
                    vscode.Uri.file(hit.absolutePath),
                    new vscode.Range(Math.max(0, hit.startLine - 1), 0, Math.max(0, hit.endLine - 1), 0)
                ));
            }
            if (response.results.length === 0) {
                stream.markdown('No matches in the local code index. Try identifiers such as class, method or config key names.');
                return {};
            }

            const model = (request as { model?: vscode.LanguageModelChat }).model;
            if (request.command === 'search' || !model) {
                stream.markdown(renderHits(response.results));
                return {};
            }

            const prompt = `${INSTRUCTIONS}${response.text}\n\nQuestion: ${request.prompt}`;
            const reply = await model.sendRequest([vscode.LanguageModelChatMessage.User(prompt)], {}, token);
            for await (const fragment of reply.text) {
                stream.markdown(fragment);
            }
        } catch (err) {
            stream.markdown(`VatStash could not answer: ${err instanceof Error ? err.message : String(err)}`);
        }
        return {};
    };

    try {
        const participant = vscode.chat.createChatParticipant(PARTICIPANT_ID, handler);
        participant.iconPath = new vscode.ThemeIcon('search');
        context.subscriptions.push(participant);
    } catch {
        // Missing or mismatched contribution on this host: skip rather than fail activation.
    }
}
