package com.contextcompresso.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachePrefixMonitorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CachePrefixMonitor monitor = new CachePrefixMonitor();

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void firstRequestForSessionIsFirstSeen() throws Exception {
        JsonNode root = parse("{\"system\":\"be helpful\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        CachePrefixMonitor.PrefixCheck check = monitor.check("sess-1", root);
        assertThat(check.firstSeen()).isTrue();
        assertThat(check.diverged()).isFalse();
    }

    @Test
    void identicalPrefixOnSubsequentTurnIsStable() throws Exception {
        JsonNode turn1 = parse("{\"system\":\"be helpful\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode turn2 = parse("{\"system\":\"be helpful\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}," +
                "{\"role\":\"assistant\",\"content\":\"hello\"},{\"role\":\"user\",\"content\":\"more\"}]}");

        monitor.check("sess-2", turn1);
        CachePrefixMonitor.PrefixCheck check = monitor.check("sess-2", turn2);

        assertThat(check.diverged()).isFalse();
        assertThat(check.firstSeen()).isFalse();
    }

    @Test
    void changedSystemPromptIsDetectedAsDivergence() throws Exception {
        JsonNode turn1 = parse("{\"system\":\"be helpful\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode turn2 = parse("{\"system\":\"be terse\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        monitor.check("sess-3", turn1);
        CachePrefixMonitor.PrefixCheck check = monitor.check("sess-3", turn2);

        assertThat(check.diverged()).isTrue();
    }

    @Test
    void changedEarlyMessageIsDetectedAsDivergence() throws Exception {
        JsonNode turn1 = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}," +
                "{\"role\":\"assistant\",\"content\":\"hello\"}]}");
        JsonNode turn2 = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}," +
                "{\"role\":\"assistant\",\"content\":\"DIFFERENT REPLY\"}]}");

        monitor.check("sess-4", turn1);
        CachePrefixMonitor.PrefixCheck check = monitor.check("sess-4", turn2);

        assertThat(check.diverged()).isTrue();
    }

    @Test
    void nullSessionKeyIsTreatedAsFirstSeen() throws Exception {
        JsonNode root = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        CachePrefixMonitor.PrefixCheck check = monitor.check(null, root);
        assertThat(check.firstSeen()).isTrue();
    }
}
