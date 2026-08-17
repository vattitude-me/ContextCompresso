package com.contextcompresso.compression;

import com.contextcompresso.config.CompressionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmartCrusherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SmartCrusher crusher = new SmartCrusher(
            new CompressionProperties(true, 20, 8000, true, 5, 3, 200, 2000, true, 2000));

    @Test
    void prunesNullFields() {
        ObjectNode node = mapper.createObjectNode();
        node.put("keep", "value");
        node.putNull("dropMe");
        node.put("emptyString", "");

        JsonNode result = crusher.crush(node);

        assertThat(result.has("dropMe")).isFalse();
        assertThat(result.has("emptyString")).isFalse();
        assertThat(result.get("keep").asText()).isEqualTo("value");
    }

    @Test
    void deduplicatesKeysLastWins() throws Exception {
        // Jackson's ObjectMapper already applies last-wins on parse of duplicate keys;
        // verify the rebuild in crush() preserves that behavior.
        String json = "{\"a\":1,\"a\":2}";
        JsonNode parsed = mapper.readTree(json);
        JsonNode result = crusher.crush(parsed);
        assertThat(result.get("a").asInt()).isEqualTo(2);
    }

    @Test
    void truncatesLargeArraysWithSentinel() {
        ArrayNode array = mapper.createArrayNode();
        for (int i = 0; i < 50; i++) {
            array.add(i);
        }
        JsonNode result = crusher.crush(array);
        ArrayNode resultArray = (ArrayNode) result;

        assertThat(resultArray.size()).isLessThanOrEqualTo(21);
        boolean hasSentinel = false;
        for (JsonNode n : resultArray) {
            if (n.isTextual() && n.asText().contains("truncated")) {
                hasSentinel = true;
            }
        }
        assertThat(hasSentinel).isTrue();
        assertThat(resultArray.get(0).asInt()).isEqualTo(0);
        assertThat(resultArray.get(resultArray.size() - 1).asInt()).isEqualTo(49);
    }

    @Test
    void collapsesWhitespaceRuns() {
        ObjectNode node = mapper.createObjectNode();
        node.put("text", "hello    world");
        JsonNode result = crusher.crush(node);
        assertThat(result.get("text").asText()).isEqualTo("hello world");
    }

    // Regression: crushMessagesArray must never apply the array-truncation-with-sentinel
    // behavior to the messages array itself. Anthropic validates message count and
    // tool_use/tool_result pairing on this array directly, so splicing a sentinel string in
    // (as crush()/crushArray does for any oversized array) produces an invalid request.
    @Test
    void crushMessagesArrayNeverTruncatesEvenWhenOverLimit() {
        ArrayNode messages = mapper.createArrayNode();
        for (int i = 0; i < 50; i++) {
            ObjectNode message = mapper.createObjectNode();
            message.put("role", i % 2 == 0 ? "user" : "assistant");
            message.put("content", "message " + i);
            messages.add(message);
        }

        JsonNode result = crusher.crushMessagesArray(messages);
        ArrayNode resultArray = (ArrayNode) result;

        assertThat(resultArray.size()).isEqualTo(50);
        for (JsonNode n : resultArray) {
            assertThat(n.isObject()).isTrue();
        }
    }

    // Regression: a no-arg tool_use's "input": {} is a required field on the content block
    // and must survive crush() even though {} would normally be pruned as empty.
    @Test
    void preservesEmptyInputOnToolUseBlock() {
        ObjectNode toolUse = mapper.createObjectNode();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "toolu_01X");
        toolUse.put("name", "Bash");
        toolUse.putObject("input");

        JsonNode result = crusher.crush(toolUse);

        assertThat(result.has("input")).isTrue();
        assertThat(result.get("input").isObject()).isTrue();
        assertThat(result.get("input").isEmpty()).isTrue();
    }

    // Regression: a tool_result's content array can legitimately be empty; it must not be
    // dropped from the block since Anthropic's schema requires the field to be present.
    @Test
    void preservesEmptyContentArrayOnToolResultBlock() {
        ObjectNode toolResult = mapper.createObjectNode();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", "toolu_01X");
        toolResult.putArray("content");

        JsonNode result = crusher.crush(toolResult);

        assertThat(result.has("content")).isTrue();
        assertThat(result.get("content").isArray()).isTrue();
    }

    // Non-content-block objects (no "type" field) should still prune empty/null fields as
    // before — the exception is scoped to typed content blocks, not applied globally.
    @Test
    void stillPrunesEmptyFieldsOnNonContentBlockObjects() {
        ObjectNode node = mapper.createObjectNode();
        node.put("keep", "value");
        node.putObject("emptyObject");
        node.putArray("emptyArray");

        JsonNode result = crusher.crush(node);

        assertThat(result.has("emptyObject")).isFalse();
        assertThat(result.has("emptyArray")).isFalse();
        assertThat(result.get("keep").asText()).isEqualTo("value");
    }
}
