package com.contextcompresso.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses provider `usage` blocks out of upstream response bytes. Best-effort only: a missing
 * or malformed block yields {@link ExtractedUsage#EMPTY} and must never propagate as an error,
 * matching the fail-open contract used throughout the compression pipeline.
 */
@Component
public class UsageExtractor {

    private static final Logger log = LoggerFactory.getLogger(UsageExtractor.class);

    private final ObjectMapper objectMapper;

    public UsageExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ExtractedUsage(long inputTokens, long outputTokens, long cacheReadTokens,
                                  long cacheWriteTokens, String model) {
        public static final ExtractedUsage EMPTY = new ExtractedUsage(0, 0, 0, 0, null);

        public boolean isEmpty() {
            return inputTokens == 0 && outputTokens == 0 && cacheReadTokens == 0 && cacheWriteTokens == 0;
        }

        /**
         * Anthropic splits usage across SSE events: `message_start` reports a placeholder
         * `output_tokens` (typically 1) alongside the real input/cache counts, and the later
         * `message_delta` reports the true final `output_tokens`. So later non-zero values
         * overwrite earlier ones rather than being maxed — the last event to report a field
         * is authoritative, not the largest value seen.
         */
        ExtractedUsage mergeWith(ExtractedUsage other) {
            return new ExtractedUsage(
                    other.inputTokens != 0 ? other.inputTokens : inputTokens,
                    other.outputTokens != 0 ? other.outputTokens : outputTokens,
                    other.cacheReadTokens != 0 ? other.cacheReadTokens : cacheReadTokens,
                    other.cacheWriteTokens != 0 ? other.cacheWriteTokens : cacheWriteTokens,
                    other.model != null ? other.model : model);
        }
    }

    /** Non-streaming JSON body: a single top-level `usage` object. */
    public ExtractedUsage fromJsonBody(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return fromNode(root);
        } catch (Exception e) {
            log.debug("Usage extraction: non-streaming body was not parseable JSON, skipping", e);
            return ExtractedUsage.EMPTY;
        }
    }

    /**
     * SSE stream: usage is split across events. Anthropic's `message_start` carries
     * input/cache counts, `message_delta` carries the final `output_tokens`. OpenAI's final
     * chunk (when `stream_options.include_usage` is set) carries a top-level `usage` object.
     * Scans every `data: {...}` line in the accumulated chunk and merges whatever is found.
     */
    public ExtractedUsage fromSseChunk(String text) {
        ExtractedUsage merged = ExtractedUsage.EMPTY;
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).strip();
            if (payload.isEmpty() || payload.equals("[DONE]")) {
                continue;
            }
            try {
                JsonNode event = objectMapper.readTree(payload);
                ExtractedUsage found = fromNode(event);
                if (!found.isEmpty()) {
                    merged = merged.mergeWith(found);
                }
            } catch (Exception e) {
                // partial/incomplete SSE frame mid-stream — expected, not an error
            }
        }
        return merged;
    }

    private ExtractedUsage fromNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return ExtractedUsage.EMPTY;
        }

        String model = textOrNull(root.get("model"));

        JsonNode usage = root.get("usage");
        if (usage == null) {
            JsonNode message = root.get("message");
            if (message != null) {
                usage = message.get("usage");
                if (model == null) {
                    model = textOrNull(message.get("model"));
                }
            }
        }
        if (usage == null || !usage.isObject()) {
            return new ExtractedUsage(0, 0, 0, 0, model);
        }

        // Anthropic shape. `message_delta`'s usage object carries only `output_tokens`
        // (the final count), with none of the other Anthropic-only fields present — still
        // route it through this branch rather than falling through to "no usage found".
        if (usage.has("input_tokens") || usage.has("cache_read_input_tokens")
                || usage.has("cache_creation_input_tokens") || usage.has("output_tokens")) {
            return new ExtractedUsage(
                    longOrZero(usage, "input_tokens"),
                    longOrZero(usage, "output_tokens"),
                    longOrZero(usage, "cache_read_input_tokens"),
                    longOrZero(usage, "cache_creation_input_tokens"),
                    model);
        }

        // OpenAI shape
        if (usage.has("prompt_tokens") || usage.has("completion_tokens")) {
            long cacheRead = 0;
            JsonNode details = usage.get("prompt_tokens_details");
            if (details != null && details.has("cached_tokens")) {
                cacheRead = longOrZero(details, "cached_tokens");
            }
            return new ExtractedUsage(
                    longOrZero(usage, "prompt_tokens"),
                    longOrZero(usage, "completion_tokens"),
                    cacheRead,
                    0,
                    model);
        }

        return new ExtractedUsage(0, 0, 0, 0, model);
    }

    private long longOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : 0;
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
