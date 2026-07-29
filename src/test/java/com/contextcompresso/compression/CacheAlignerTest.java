package com.contextcompresso.compression;

import com.contextcompresso.provider.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheAlignerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CacheAligner aligner = new CacheAligner();

    private ArrayNode messages(String... roleContentPairs) {
        ArrayNode array = mapper.createArrayNode();
        for (int i = 0; i < roleContentPairs.length; i += 2) {
            var obj = mapper.createObjectNode();
            obj.put("role", roleContentPairs[i]);
            obj.put("content", roleContentPairs[i + 1]);
            array.add(obj);
        }
        return array;
    }

    @Test
    void claudeModePreservesOriginalOrder() {
        ArrayNode original = messages("user", "hi", "system", "sys prompt", "assistant", "hello");
        JsonNode result = aligner.align(original, Provider.CLAUDE);
        assertThat(result).isSameAs(original);
    }

    @Test
    void copilotModePreservesLeadingCopilotSystemPrompts() {
        ArrayNode original = messages(
                "system", "copilot guardrail 1",
                "system", "copilot guardrail 2",
                "user", "do something",
                "assistant", "ok"
        );
        JsonNode result = aligner.align(original, Provider.COPILOT);
        assertThat(result.get(0).get("content").asText()).isEqualTo("copilot guardrail 1");
        assertThat(result.get(1).get("content").asText()).isEqualTo("copilot guardrail 2");
    }

    @Test
    void defaultModeMovesSystemMessagesFirst() {
        ArrayNode original = messages(
                "user", "hi",
                "assistant", "hello",
                "system", "be nice"
        );
        JsonNode result = aligner.align(original, Provider.DEFAULT);
        assertThat(result.get(0).get("role").asText()).isEqualTo("system");
    }

    @Test
    void contentHashIsStable() {
        var obj = mapper.createObjectNode();
        obj.put("role", "system");
        obj.put("content", "same content");
        String hash1 = aligner.contentHash(obj);
        String hash2 = aligner.contentHash(obj);
        assertThat(hash1).isEqualTo(hash2);
    }
}
