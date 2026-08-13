package com.contextcompresso.compression;

import com.contextcompresso.provider.Provider;
import com.contextcompresso.util.HashUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Provider-specific message ordering.
 *
 * Claude: Anthropic's prompt caching matches on exact byte prefix, so reordering
 * anything would destroy cache hits and increase cost - this mode is a pure no-op,
 * deliberately, even though other providers benefit from reordering.
 *
 * Copilot: Copilot-injected system prompts (identified by their position: the leading
 * system messages already present before any user content) must stay put, but any
 * system messages the pipeline itself would add/reorder afterwards are free to move.
 *
 * Default (OpenAI): system messages are hoisted to the front and sorted by content hash
 * for stable ordering, which improves KV-cache locality across requests with the same
 * system prompt set.
 */
@Component
public class CacheAligner {

    public JsonNode align(JsonNode messagesNode, Provider provider) {
        if (messagesNode == null || !messagesNode.isArray()) {
            return messagesNode;
        }
        return switch (provider) {
            case CLAUDE -> messagesNode;
            case COPILOT -> alignCopilot((ArrayNode) messagesNode);
            case DEFAULT -> alignDefault((ArrayNode) messagesNode);
        };
    }

    private JsonNode alignCopilot(ArrayNode messages) {
        // Leading run of system messages is Copilot-injected; preserve its position exactly.
        int leadingSystemCount = 0;
        while (leadingSystemCount < messages.size() && isSystem(messages.get(leadingSystemCount))) {
            leadingSystemCount++;
        }

        List<JsonNode> leading = new ArrayList<>();
        List<JsonNode> rest = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i < leadingSystemCount) {
                leading.add(messages.get(i));
            } else {
                rest.add(messages.get(i));
            }
        }

        // Among the remainder, hoist any user-added system messages to just after the
        // Copilot-injected block, sorted by content hash for determinism.
        List<JsonNode> restSystem = new ArrayList<>();
        List<JsonNode> restOther = new ArrayList<>();
        for (JsonNode m : rest) {
            if (isSystem(m)) {
                restSystem.add(m);
            } else {
                restOther.add(m);
            }
        }
        restSystem.sort(Comparator.comparing(this::contentHash));

        ArrayNode result = messages.arrayNode();
        leading.forEach(result::add);
        restSystem.forEach(result::add);
        restOther.forEach(result::add);
        return result;
    }

    private JsonNode alignDefault(ArrayNode messages) {
        List<JsonNode> systemMessages = new ArrayList<>();
        List<JsonNode> otherMessages = new ArrayList<>();
        for (JsonNode m : messages) {
            if (isSystem(m)) {
                systemMessages.add(m);
            } else {
                otherMessages.add(m);
            }
        }
        systemMessages.sort(Comparator.comparing(this::contentHash));

        ArrayNode result = messages.arrayNode();
        systemMessages.forEach(result::add);
        otherMessages.forEach(result::add);
        return result;
    }

    private boolean isSystem(JsonNode message) {
        JsonNode role = message.get("role");
        return role != null && "system".equals(role.asText());
    }

    public String contentHash(JsonNode message) {
        JsonNode content = message.get("content");
        String text = content == null ? "" : content.toString();
        return HashUtil.sha256Hex(text);
    }
}
