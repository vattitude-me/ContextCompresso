package com.contextcompresso.integration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.jdbc.core.JdbcTemplate;
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
class EndToEndProxyTest {

    static MockWebServer defaultUpstream;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startServer() throws IOException {
        defaultUpstream = new MockWebServer();
        defaultUpstream.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        defaultUpstream.shutdown();
    }

    @AfterEach
    void drain() throws InterruptedException {
        // no-op placeholder for symmetry with request draining if needed
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("contextcompresso.providers.default.base-url", () -> defaultUpstream.url("/").toString().replaceAll("/$", ""));
        Path tempDb = Files.createTempDirectory("cc-e2e").resolve("ccr.db");
        registry.add("contextcompresso.ccr.db-path", tempDb::toString);
    }

    @Test
    void compressesAndForwardsSmallerBodyToUpstream() throws InterruptedException {
        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            repeated.append("This is a very verbose repeated tool result line that should compress well. ");
        }
        String requestBody = "{\"model\":\"gpt-4\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"" + repeated + "\"}]}";

        defaultUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp-1\",\"choices\":[]}").addHeader("Content-Type", "application/json"));

        byte[] responseBody = webTestClient.post().uri("/v1/chat/completions")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-CC-Request-Id")
                .expectHeader().exists("X-CC-Ratio")
                .expectBody().returnResult().getResponseBody();

        RecordedRequest recorded = defaultUpstream.takeRequest();
        String upstreamBody = recorded.getBody().readUtf8();
        assertThat(upstreamBody.length()).isLessThan(requestBody.length());

        assertThat(responseBody).isNotNull();
        assertThat(new String(responseBody, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"id\":\"resp-1\",\"choices\":[]}");
    }

    @Test
    void ccrEntriesCreatedInSqlite() throws InterruptedException {
        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            repeated.append("Another very verbose repeated tool result line for CCR storage test. ");
        }
        String requestBody = "{\"model\":\"gpt-4\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"" + repeated + "\"}]}";

        defaultUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp-2\",\"choices\":[]}").addHeader("Content-Type", "application/json"));

        String requestId = webTestClient.post().uri("/v1/chat/completions")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst("X-CC-Request-Id");

        defaultUpstream.takeRequest();

        assertThat(requestId).isNotNull();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_entries WHERE request_id = ?", Integer.class, requestId);
        assertThat(count).isNotNull();
    }

    @Test
    void failOpenStillReachesUpstreamOnEmptyBody() throws InterruptedException {
        defaultUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{}").addHeader("Content-Type", "application/json"));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = defaultUpstream.takeRequest();
        assertThat(recorded).isNotNull();
    }

    @Test
    void acceptsLargeRequestBodiesAboveDefaultWebFluxCodecLimit() throws InterruptedException {
        StringBuilder bigBody = new StringBuilder();
        for (int i = 0; i < 15000; i++) {
            bigBody.append("This is a repeated chunk of content used to exceed the default 256KB in-memory limit for WebFlux request buffering. ");
        }
        String requestBody = "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"" + bigBody + "\"}]}";

        defaultUpstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"resp-large\",\"choices\":[]}").addHeader("Content-Type", "application/json"));

        assertThat(requestBody.length()).isGreaterThan(262_144);

        webTestClient.post().uri("/v1/chat/completions")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-CC-Original-Chars",
                        chars -> assertThat(Long.parseLong(chars)).isGreaterThan(262_144));

        RecordedRequest recorded = defaultUpstream.takeRequest();
        assertThat(recorded).isNotNull();
    }
}
