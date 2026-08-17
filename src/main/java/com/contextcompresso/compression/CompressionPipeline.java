package com.contextcompresso.compression;

import com.contextcompresso.ccr.CcrEntry;
import com.contextcompresso.ccr.CcrStore;
import com.contextcompresso.config.CompressionProperties;
import com.contextcompresso.model.CompressedRequest;
import com.contextcompresso.model.CompressionStats;
import com.contextcompresso.provider.ProviderConfig;
import com.contextcompresso.tokenizer.AnthropicEstimator;
import com.contextcompresso.tokenizer.CharRatioEstimator;
import com.contextcompresso.tokenizer.JtokkitEstimator;
import com.contextcompresso.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Orchestrates CacheAligner -> SmartCrusher -> CodeCompressor -> TextTruncator -> CcrStore,
 * provider-aware, wrapped in a fail-open try/catch: any exception anywhere in the pipeline
 * results in the original body being forwarded untouched. A compression bug must never
 * turn into a user-facing 500.
 */
@Component
public class CompressionPipeline {

    private static final Logger log = LoggerFactory.getLogger(CompressionPipeline.class);

    private final CacheAligner cacheAligner;
    private final SmartCrusher smartCrusher;
    private final CodeCompressor codeCompressor;
    private final TextTruncator textTruncator;
    private final ToolResultCompactor toolResultCompactor;
    private final CcrStore ccrStore;
    private final CompressionProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final JtokkitEstimator jtokkitEstimator;
    private final AnthropicEstimator anthropicEstimator;
    private final CharRatioEstimator charRatioEstimator;

    public CompressionPipeline(CacheAligner cacheAligner,
                                SmartCrusher smartCrusher,
                                CodeCompressor codeCompressor,
                                TextTruncator textTruncator,
                                ToolResultCompactor toolResultCompactor,
                                CcrStore ccrStore,
                                CompressionProperties properties,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper,
                                JtokkitEstimator jtokkitEstimator,
                                AnthropicEstimator anthropicEstimator,
                                CharRatioEstimator charRatioEstimator) {
        this.cacheAligner = cacheAligner;
        this.smartCrusher = smartCrusher;
        this.codeCompressor = codeCompressor;
        this.textTruncator = textTruncator;
        this.toolResultCompactor = toolResultCompactor;
        this.ccrStore = ccrStore;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.jtokkitEstimator = jtokkitEstimator;
        this.anthropicEstimator = anthropicEstimator;
        this.charRatioEstimator = charRatioEstimator;
    }

    public CompressedRequest compress(String rawBody, ProviderConfig config, String requestId) {
        int originalChars = rawBody == null ? 0 : rawBody.length();
        boolean streaming = rawBody != null && rawBody.contains("\"stream\"") && rawBody.contains("true");

        if (!properties.enabled()) {
            meterRegistry.counter("cc.compression.skipped", "reason", "disabled").increment();
            return passthrough(rawBody, requestId, streaming, config);
        }
        if (originalChars < properties.minCompressChars()) {
            meterRegistry.counter("cc.compression.skipped", "reason", "below-threshold").increment();
            return passthrough(rawBody, requestId, streaming, config);
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            TokenEstimator estimator = resolveEstimator(config.tokenEstimator());
            List<CcrEntry> ccrEntries = new ArrayList<>();

            JsonNode compressedRoot = runPipeline(root, config, estimator, requestId, ccrEntries);

            if (!ccrEntries.isEmpty()) {
                ccrStore.storeAll(requestId, ccrEntries);
            }

            String compressedBody = objectMapper.writeValueAsString(compressedRoot);
            int compressedChars = compressedBody.length();
            CompressionStats stats = CompressionStats.of(originalChars, compressedChars);

            meterRegistry.summary("cc.compression.ratio", "provider", config.name().name())
                    .record(stats.ratio());
            meterRegistry.counter("cc.chars.saved", "provider", config.name().name())
                    .increment(Math.max(originalChars - compressedChars, 0));

            boolean isStreaming = detectStreaming(compressedRoot);
            return new CompressedRequest(compressedRoot, requestId, stats, isStreaming);
        } catch (Exception e) {
            log.warn("Compression pipeline failed, falling back to original body (fail-open). requestId={}",
                    requestId, e);
            meterRegistry.counter("cc.compression.skipped", "reason", "error").increment();
            return passthrough(rawBody, requestId, streaming, config);
        }
    }

    private CompressedRequest passthrough(String rawBody, String requestId, boolean streaming, ProviderConfig config) {
        JsonNode node;
        try {
            node = rawBody == null || rawBody.isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawBody);
        } catch (Exception e) {
            node = objectMapper.createObjectNode();
        }
        int chars = rawBody == null ? 0 : rawBody.length();
        return new CompressedRequest(node, requestId, CompressionStats.of(chars, chars), streaming);
    }

    private JsonNode runPipeline(JsonNode root, ProviderConfig config, TokenEstimator estimator,
                                  String requestId, List<CcrEntry> ccrEntries) {
        ObjectNode result = root.isObject() ? ((ObjectNode) root).deepCopy() : objectMapper.createObjectNode();

        JsonNode messages = result.get("messages");
        if (messages != null && messages.isArray()) {
            JsonNode aligned = cacheAligner.align(messages, config.name());
            JsonNode crushed = smartCrusher.crushMessagesArray((ArrayNode) aligned);

            ArrayNode processedMessages = (ArrayNode) crushed;
            for (int i = 0; i < processedMessages.size(); i++) {
                JsonNode message = processedMessages.get(i);
                processMessageContent(message, config, estimator, requestId, i, ccrEntries);
            }
            result.set("messages", processedMessages);
        }
        return result;
    }

    private void processMessageContent(JsonNode message, ProviderConfig config, TokenEstimator estimator,
                                        String requestId, int messageIdx, List<CcrEntry> ccrEntries) {
        if (!(message instanceof ObjectNode messageObj)) {
            return;
        }
        JsonNode content = messageObj.get("content");
        if (content == null) {
            return;
        }
        if (content.isTextual()) {
            String original = content.asText();
            String compressed = compressText(original, config, estimator);
            if (!compressed.equals(original)) {
                recordCcr(requestId, messageIdx, "content", original, compressed, ccrEntries);
            }
            messageObj.set("content", new TextNode(compressed));
        } else if (content.isArray()) {
            ArrayNode blocks = (ArrayNode) content;
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode block = blocks.get(i);
                if (!(block instanceof ObjectNode blockObj)) {
                    continue;
                }
                if (isToolResult(blockObj) && properties.toolResultCompactionEnabled()) {
                    compactToolResultBlock(blockObj, estimator, requestId, messageIdx, i, ccrEntries);
                    continue;
                }
                // never touch cache_control-bearing blocks structurally beyond text compression
                JsonNode textNode = blockObj.get("text");
                if (textNode != null && textNode.isTextual()) {
                    String original = textNode.asText();
                    String compressed = compressText(original, config, estimator);
                    if (!compressed.equals(original)) {
                        recordCcr(requestId, messageIdx, "content[" + i + "].text", original, compressed, ccrEntries);
                    }
                    blockObj.set("text", new TextNode(compressed));
                }
            }
        }
    }

    private boolean isToolResult(JsonNode block) {
        JsonNode type = block.get("type");
        return type != null && "tool_result".equals(type.asText());
    }

    /**
     * tool_result content is either a bare string or an array of {type:"text", text:...}
     * blocks. Either way it sits after the cached system-prompt/early-turn prefix, so
     * compacting it here does not invalidate Anthropic's prompt cache the way rewriting
     * earlier messages would — this is the highest-value compression target for
     * command-heavy sessions (grep dumps, file reads, test output).
     */
    private void compactToolResultBlock(ObjectNode blockObj, TokenEstimator estimator, String requestId,
                                         int messageIdx, int blockIdx, List<CcrEntry> ccrEntries) {
        JsonNode innerContent = blockObj.get("content");
        if (innerContent == null) {
            return;
        }
        int threshold = toolResultThresholdTokens();

        if (innerContent.isTextual()) {
            String original = innerContent.asText();
            String compressed = toolResultCompactor.compact(original, threshold, estimator);
            if (!compressed.equals(original)) {
                recordCcr(requestId, messageIdx, "content[" + blockIdx + "].content", original, compressed, ccrEntries);
            }
            blockObj.set("content", new TextNode(compressed));
        } else if (innerContent.isArray()) {
            ArrayNode innerBlocks = (ArrayNode) innerContent;
            for (int j = 0; j < innerBlocks.size(); j++) {
                if (!(innerBlocks.get(j) instanceof ObjectNode innerObj)) {
                    continue;
                }
                JsonNode textNode = innerObj.get("text");
                if (textNode == null || !textNode.isTextual()) {
                    continue;
                }
                String original = textNode.asText();
                String compressed = toolResultCompactor.compact(original, threshold, estimator);
                if (!compressed.equals(original)) {
                    recordCcr(requestId, messageIdx,
                            "content[" + blockIdx + "].content[" + j + "].text", original, compressed, ccrEntries);
                }
                innerObj.set("text", new TextNode(compressed));
            }
        }
    }

    private int toolResultThresholdTokens() {
        // deliberately tighter than the general message threshold — tool output has no
        // narrative structure worth preserving in the middle, unlike prose
        return Math.max(properties.maxToolResultChars() / 4, 50);
    }

    private String compressText(String text, ProviderConfig config, TokenEstimator estimator) {
        String working = text;
        if (config.codeCompressionEnabled()) {
            working = codeCompressor.compressMarkdown(working, config.aggressiveCodeStrip());
        }
        if (properties.textTruncationEnabled()) {
            int tokenThreshold = estimateThresholdTokens(config);
            working = textTruncator.truncate(working, tokenThreshold,
                    properties.truncationHeadSentences(), properties.truncationTailSentences(), estimator);
        }
        return working;
    }

    private int estimateThresholdTokens(ProviderConfig config) {
        // proportion of max-message-chars as a token budget, roughly chars/4 as a floor
        return Math.max(properties.maxMessageChars() / 4, 50);
    }

    private void recordCcr(String requestId, int messageIdx, String contentKey, String original,
                            String compressed, List<CcrEntry> ccrEntries) {
        int charDelta = compressed.length() - original.length();
        String id = CcrStore.computeId(requestId, messageIdx, contentKey);
        ccrEntries.add(new CcrEntry(id, requestId, messageIdx, contentKey, original, compressed,
                charDelta, System.currentTimeMillis()));
    }

    private boolean detectStreaming(JsonNode root) {
        JsonNode stream = root.get("stream");
        return stream != null && stream.asBoolean(false);
    }

    private TokenEstimator resolveEstimator(String estimatorName) {
        if (estimatorName == null) {
            return charRatioEstimator;
        }
        return switch (estimatorName.toLowerCase()) {
            case "jtokkit" -> jtokkitEstimator;
            case "anthropic" -> anthropicEstimator;
            default -> charRatioEstimator;
        };
    }
}
