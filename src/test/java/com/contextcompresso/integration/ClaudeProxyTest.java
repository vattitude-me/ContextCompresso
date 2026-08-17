package com.contextcompresso.integration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@ActiveProfiles("test")
class ClaudeProxyTest {

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
        registry.add("contextcompresso.providers.claude.base-url", () -> claudeUpstream.url("/").toString().replaceAll("/$", ""));
        Path tempDb = Files.createTempDirectory("cc-claude").resolve("ccr.db");
        registry.add("contextcompresso.ccr.db-path", tempDb::toString);
    }

    @Test
    void cacheControlBlocksPreserved() throws InterruptedException {
        String requestBody = "{\"model\":\"claude-3-opus\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\",\"cache_control\":{\"type\":\"ephemeral\"}}]}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"cache_control\"");
        assertThat(body).contains("ephemeral");
    }

    @Test
    void messageOrderUnchanged() throws InterruptedException {
        String requestBody = "{\"model\":\"claude-3-opus\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"first\"}," +
                "{\"role\":\"assistant\",\"content\":\"second\"}," +
                "{\"role\":\"system\",\"content\":\"third\"}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        String body = recorded.getBody().readUtf8();
        int firstIdx = body.indexOf("first");
        int secondIdx = body.indexOf("second");
        int thirdIdx = body.indexOf("third");
        assertThat(firstIdx).isLessThan(secondIdx);
        assertThat(secondIdx).isLessThan(thirdIdx);
    }

    @Test
    void apiKeyHeaderForwarded() throws InterruptedException {
        String requestBody = "{\"model\":\"claude-3-opus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        assertThat(recorded.getHeaders().get("X-Api-Key")).isEqualTo("sk-ant-test123");
    }

    // Regression: Authorization was absent from the claude forward-headers list, so OAuth
    // subscription requests (Claude Code signed in without an API key) reached Anthropic with
    // no credential at all and came back 401 "x-api-key header is required".
    @Test
    void oauthAuthorizationHeaderForwarded() throws InterruptedException {
        String requestBody = "{\"model\":\"claude-3-opus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("Authorization", "Bearer sk-ant-oat01-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        assertThat(recorded.getHeaders().get("Authorization")).isEqualTo("Bearer sk-ant-oat01-test123");
    }

    @Test
    void largeToolResultsTruncated() throws InterruptedException {
        StringBuilder largeResult = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeResult.append("Line of grep output number ").append(i).append(". ");
        }
        String requestBody = "{\"model\":\"claude-3-opus\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"" + largeResult + "\"}]}";

        claudeUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/messages")
                .header("X-Api-Key", "sk-ant-test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = claudeUpstream.takeRequest();
        assertThat(recorded.getBody().readUtf8().length()).isLessThan(requestBody.length());
    }
}
