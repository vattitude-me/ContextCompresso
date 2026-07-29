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
class CopilotProxyTest {

    static MockWebServer copilotUpstream;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void startServer() throws IOException {
        copilotUpstream = new MockWebServer();
        copilotUpstream.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        copilotUpstream.shutdown();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("contextcompresso.providers.copilot.base-url", () -> copilotUpstream.url("/").toString().replaceAll("/$", ""));
        Path tempDb = Files.createTempDirectory("cc-copilot").resolve("ccr.db");
        registry.add("contextcompresso.ccr.db-path", tempDb::toString);
    }

    @Test
    void copilotSystemPromptsPreservedVerbatim() throws InterruptedException {
        String systemPrompt = "You are GitHub Copilot, an AI programming assistant.";
        String requestBody = "{\"model\":\"gpt-4\",\"messages\":[" +
                "{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"}," +
                "{\"role\":\"user\",\"content\":\"write a function\"}]}";

        copilotUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer ghp_test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = copilotUpstream.takeRequest();
        assertThat(recorded.getBody().readUtf8()).contains(systemPrompt);
    }

    @Test
    void toolCallsForwardedUnchanged() throws InterruptedException {
        String requestBody = "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
                "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"getWeather\",\"arguments\":\"{}\"}}]}";

        copilotUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer ghp_test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = copilotUpstream.takeRequest();
        assertThat(recorded.getBody().readUtf8()).contains("getWeather");
    }

    @Test
    void authHeaderForwarded() throws InterruptedException {
        String requestBody = "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        copilotUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp\"}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer ghp_test123")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = copilotUpstream.takeRequest();
        assertThat(recorded.getHeaders().get("Authorization")).isEqualTo("Bearer ghp_test123");
    }
}
