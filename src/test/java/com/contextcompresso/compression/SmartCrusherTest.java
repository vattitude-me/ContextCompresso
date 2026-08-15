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
}
