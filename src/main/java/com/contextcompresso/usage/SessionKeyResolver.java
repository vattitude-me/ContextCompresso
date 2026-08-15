package com.contextcompresso.usage;

import com.contextcompresso.util.HashUtil;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Derives a stable identifier for a conversation from a stateless request by hashing its
 * earliest messages. Claude/Copilot resend the full transcript on every turn, so the prefix
 * (system prompt + first messages) stays byte-identical across a session's turns while later
 * messages keep growing — hashing only the prefix lets requests from the same conversation
 * collide on the same key without the proxy holding any session state itself.
 */
public final class SessionKeyResolver {

    private SessionKeyResolver() {
    }

    /**
     * Hashes only the system prompt and the very first message. Later messages in the
     * "prefix" are not actually stable: message index 1 is empty on turn 1 (just the user's
     * opener) but holds the assistant's reply by turn 2, so including it would change the
     * key mid-conversation. Only index 0 is guaranteed present and unchanged from the first
     * turn onward.
     */
    public static String resolve(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        StringBuilder basis = new StringBuilder();

        JsonNode system = root.get("system");
        if (system != null) {
            basis.append(system.isTextual() ? system.asText() : system.toString());
        }

        JsonNode messages = root.get("messages");
        if (messages != null && messages.isArray() && !messages.isEmpty()) {
            basis.append('|').append(messages.get(0).toString());
        }

        if (basis.isEmpty()) {
            return null;
        }
        return HashUtil.sha256Hex(basis.toString());
    }
}
