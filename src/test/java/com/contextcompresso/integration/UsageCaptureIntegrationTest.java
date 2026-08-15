package com.contextcompresso.integration;

import com.contextcompresso.usage.UsageRecord;
import com.contextcompresso.usage.UsageStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@ActiveProfiles("test")
class UsageCaptureIntegrationTest {

    static MockWebServer claudeUpstream;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UsageStore usageStore;

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
        Path tempDb = Files.createTempDirectory("cc-usage").resolve("ccr.db");
        registry.add("contextcompresso.ccr.db-path", tempDb::toString);
    }

    @Test
    void nonStreamingResponseUsageIsCapturedAndPersisted() {
        String requestBody = "{\"model\":\"claude-opus-5\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"hello there\"}]}";
        String upstreamResponse = "{\"id\":\"msg_1\",\"model\":\"claude-opus-5\"," +
                "\"usage\":{\"input_tokens\":42,\"output_tokens\":17," +
                "\"cache_read_input_tokens\":900,\"cache_creation_input_tokens\":10}}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody(upstreamResponse).addHeader("Content-Type", "application/json"));

        String requestId = webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseHeaders().getFirst("X-CC-Request-Id");

        assertThat(requestId).isNotNull();

        UsageRecord record = awaitRecord(requestId)
                .orElseThrow(() -> new AssertionError("no usage record for requestId=" + requestId));

        assertThat(record.inputTokens()).isEqualTo(42);
        assertThat(record.outputTokens()).isEqualTo(17);
        assertThat(record.cacheReadTokens()).isEqualTo(900);
        assertThat(record.cacheWriteTokens()).isEqualTo(10);
        assertThat(record.model()).isEqualTo("claude-opus-5");
        assertThat(record.sessionKey()).isNotNull();
    }

    @Test
    void malformedUsageBodyDoesNotBreakResponseAndRecordsNothing() {
        String requestBody = "{\"model\":\"claude-opus-5\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"hello again\"}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("not valid json at all").addHeader("Content-Type", "application/json"));

        String requestId = webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("not valid json at all")
                .returnResult().getResponseHeaders().getFirst("X-CC-Request-Id");

        assertThat(requestId).isNotNull();
        // give the async persistence a moment, then confirm nothing bad was recorded
        sleepQuietly(500);
        assertThat(usageStore.findSince(0L)).noneMatch(r -> r.requestId().equals(requestId));
    }

    private Optional<UsageRecord> awaitRecord(String requestId) {
        Supplier<Optional<UsageRecord>> lookup = () -> usageStore.findSince(0L).stream()
                .filter(r -> r.requestId().equals(requestId))
                .findFirst();
        long deadline = System.currentTimeMillis() + 5000;
        Optional<UsageRecord> found = lookup.get();
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(50);
            found = lookup.get();
        }
        return found;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
