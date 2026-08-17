package com.contextcompresso.integration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@ActiveProfiles("test")
class ToolResultCompactionIntegrationTest {

    static MockWebServer claudeUpstream;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void startServer() throws IOException {
        claudeUpstream = new MockWebServer();
        claudeUpstream.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        claudeUpstream.shutdown();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("contextcompresso.providers.claude.base-url",
                () -> claudeUpstream.url("/").toString().replaceAll("/$", ""));
        Path tempDb = Files.createTempDirectory("cc-toolresult").resolve("ccr.db");
        registry.add("contextcompresso.ccr.db-path", tempDb::toString);
    }

    @Test
    void largeToolResultBlockIsCompactedIndependentlyOfSurroundingText() throws InterruptedException {
        StringBuilder grepDump = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            grepDump.append("src/file").append(i).append(".java:12: match found on this line.\\n");
        }

        String requestBody = "{\"model\":\"claude-opus-5\",\"messages\":[" +
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"grep\"}]}," +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"tool_result\",\"tool_use_id\":\"tool_1\",\"content\":\"" + grepDump + "\"}" +
                "]}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        String sentBody = recorded.getBody().readUtf8();

        assertThat(sentBody.length()).isLessThan(requestBody.length());
        assertThat(sentBody).contains("tool_result");
        assertThat(sentBody).contains("tool_use_id");
        // structural integrity: the tool_use/tool_result pairing must survive compaction,
        // or the Anthropic API rejects the request outright
        assertThat(sentBody).contains("tool_1");
    }

    // Regression: SmartCrusher used to treat the top-level messages array like any other
    // oversized array, slicing out the middle and splicing in a "[...truncated...]" text
    // sentinel once the conversation passed max-array-elements (20 by default). That both
    // deleted whole messages and inserted a bare string where Anthropic expects a
    // {role, content} object - producing "messages.N: Input does not match the expected
    // shape" on any real session past ~20 messages. This sends 30 messages with a
    // tool_use/tool_result pair straddling the old cutoff and asserts nothing is dropped.
    @Test
    void messagesArrayIsNeverTruncatedRegardlessOfCount() throws InterruptedException {
        StringBuilder messages = new StringBuilder("[");
        for (int i = 0; i < 14; i++) {
            if (i > 0) {
                messages.append(",");
            }
            messages.append("{\"role\":\"user\",\"content\":\"turn ").append(i).append("\"},")
                    .append("{\"role\":\"assistant\",\"content\":\"reply ").append(i).append("\"}");
        }
        messages.append(",{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tool_straddle\",\"name\":\"grep\",\"input\":{}}]}");
        messages.append(",{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tool_straddle\",\"content\":\"no matches\"}]}");
        messages.append("]");

        String requestBody = "{\"model\":\"claude-opus-5\",\"messages\":" + messages + "}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        String sentBody = recorded.getBody().readUtf8();

        assertThat(sentBody).doesNotContain("truncated");
        assertThat(sentBody).contains("tool_straddle");
        assertThat(sentBody).contains("tool_result");
        assertThat(sentBody).contains("turn 0");
        assertThat(sentBody).contains("turn 13");
    }

    // Regression: SmartCrusher's field-pruning treated tool_use's "input":{} as prunable empty
    // metadata and stripped it, leaving a tool_use block missing a required field - also
    // rejected by Anthropic with "Input does not match the expected shape".
    @Test
    void toolUseWithEmptyInputKeepsInputField() throws InterruptedException {
        String requestBody = "{\"model\":\"claude-opus-5\",\"messages\":[" +
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tool_noargs\",\"name\":\"Bash\",\"input\":{}}]}" +
                "]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        assertThat(recorded.getBody().readUtf8()).contains("\"input\":{}");
    }

    @Test
    void shortToolResultIsLeftUntouched() throws InterruptedException {
        String requestBody = "{\"model\":\"claude-opus-5\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"tool_result\",\"tool_use_id\":\"tool_2\",\"content\":\"3 matches found\"}" +
                "]}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        assertThat(recorded.getBody().readUtf8()).contains("3 matches found");
    }
}
