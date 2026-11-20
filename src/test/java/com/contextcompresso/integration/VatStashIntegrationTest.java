package com.contextcompresso.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VatStashIntegrationTest {

    static MockWebServer defaultUpstream;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Bound to the real server so the filter sees a loopback remote address.
    // The auto-configured client is bound to the handler and has no remote address.
    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @TempDir
    Path tmp;

    Path root;

    @BeforeAll
    static void startServer() throws IOException {
        defaultUpstream = new MockWebServer();
        defaultUpstream.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        defaultUpstream.shutdown();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("contextcompresso.providers.default.base-url",
                () -> defaultUpstream.url("/").toString().replaceAll("/$", ""));
        Path tempDb = Files.createTempDirectory("cc-vatstash").resolve("ccr.db");
        registry.add("contextcompresso.ccr.db-path", tempDb::toString);
    }

    @BeforeEach
    void createWorkspace() throws IOException {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
        root = Files.createDirectory(tmp.resolve("project")).toRealPath();
        write("src/main/java/demo/PaymentService.java", "package demo;\n\npublic class PaymentService {\n\n"
                + "    public int retrychargeflow(int cents) {\n        int a = cents + 1;\n        int b = a * 2;\n"
                + "        int c = b - 3;\n        return c;\n    }\n}\n");
        write("src/main/java/demo/Ledger.java", "package demo;\n\npublic class Ledger {\n\n"
                + "    public int postentry(int cents) {\n        int a = cents + 1;\n        int b = a * 2;\n"
                + "        int c = b - 3;\n        return c;\n    }\n}\n");
        write("web/client.ts", "export function fetchbalance(id: string) {\n  const url = '/balance/' + id;\n"
                + "  const res = url.length;\n  const out = res + 1;\n  return out;\n}\n");
    }

    private void write(String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private JsonNode json(WebTestClient.ResponseSpec spec) throws IOException {
        byte[] body = spec.expectBody().returnResult().getResponseBodyContent();
        return MAPPER.readTree(body == null ? new byte[0] : body);
    }

    private String register() throws IOException {
        JsonNode res = json(webTestClient.post().uri("/vatstash/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("root", root.toString(), "client", "vscode"))
                .exchange().expectStatus().isOk());
        assertThat(res.get("root").asText()).isEqualTo(root.toString());
        return res.get("workspaceId").asText();
    }

    private JsonNode awaitReady(String id) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            last[0] = json(webTestClient.get().uri("/vatstash/workspaces/{id}/status", id).exchange()
                    .expectStatus().isOk());
            assertThat(last[0].get("state").asText()).isEqualTo("ready");
        });
        return last[0];
    }

    private JsonNode search(String id, String query) throws IOException {
        return json(webTestClient.post().uri("/vatstash/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query, "workspaceIds", List.of(id), "client", "command"))
                .exchange().expectStatus().isOk());
    }

    @Test
    void registerIndexSearchAndMetricsRoundTrip() throws IOException {
        String id = register();
        assertThat(id).hasSize(16);
        JsonNode status = awaitReady(id);
        assertThat(status.get("freshness").asText()).isEqualTo("fresh");
        assertThat(status.get("filesIndexed").asInt()).isEqualTo(3);
        assertThat(status.get("chunksIndexed").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(status.get("pendingFiles").asInt()).isZero();
        assertThat(status.get("truncated").asBoolean()).isFalse();
        assertThat(status.get("lastError").isNull()).isTrue();
        assertThat(status.get("progress").has("done")).isTrue();

        // idempotent
        assertThat(register()).isEqualTo(id);
        JsonNode list = json(webTestClient.get().uri("/vatstash/workspaces").exchange().expectStatus().isOk());
        assertThat(list.isArray()).isTrue();
        assertThat(list.findValuesAsText("workspaceId")).contains(id);

        JsonNode res = search(id, "retrychargeflow");
        assertThat(res.get("searchId").asText()).isNotBlank();
        assertThat(res.get("freshness").asText()).isEqualTo("fresh");
        JsonNode first = res.get("results").get(0);
        assertThat(first.get("path").asText()).isEqualTo("src/main/java/demo/PaymentService.java");
        assertThat(first.get("absolutePath").asText())
                .isEqualTo(root.resolve("src/main/java/demo/PaymentService.java").toString());
        assertThat(first.get("language").asText()).isEqualTo("java");
        assertThat(first.get("symbol").asText()).isEqualTo("PaymentService.retrychargeflow");
        assertThat(first.get("kind").asText()).isEqualTo("method");
        assertThat(first.get("startLine").asInt()).isEqualTo(5);
        assertThat(first.get("endLine").asInt()).isEqualTo(10);
        assertThat(first.get("content").asText()).contains("retrychargeflow");
        assertThat(first.get("workspaceId").asText()).isEqualTo(id);
        assertThat(res.at("/totals/chunksReturned").asInt()).isEqualTo(res.get("results").size());
        assertThat(res.at("/totals/sourceFileTokens").asLong()).isGreaterThan(0);
        assertThat(res.get("text").asText()).startsWith("[vatstash v1]").contains("PaymentService.java:5-10");

        JsonNode byRoot = json(webTestClient.post().uri("/vatstash/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "fetchbalance", "root", root.resolve("web").toString()))
                .exchange().expectStatus().isOk());
        assertThat(byRoot.at("/results/0/path").asText()).isEqualTo("web/client.ts");

        webTestClient.post().uri("/vatstash/search").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "  ", "workspaceIds", List.of(id)))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_request");
        webTestClient.post().uri("/vatstash/search").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "x"))
                .exchange().expectStatus().isBadRequest();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode summary = json(webTestClient.get().uri("/vatstash/metrics/summary?days=7").exchange()
                    .expectStatus().isOk());
            assertThat(summary.get("calls").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(summary.at("/byClient/command").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(summary.get("returnedTokens").asLong()).isGreaterThan(0);
            assertThat(summary.get("days").asInt()).isEqualTo(7);
            assertThat(summary.get("note").asText()).contains("estimate");
            assertThat(summary.get("byDay").isArray()).isTrue();
        });
        JsonNode clamped = json(webTestClient.get().uri("/vatstash/metrics/summary?days=500").exchange()
                .expectStatus().isOk());
        assertThat(clamped.get("days").asInt()).isEqualTo(90);

        String csv = webTestClient.get().uri("/vatstash/metrics/export?days=30&format=csv").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/csv")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(csv).startsWith("created_at_iso,client,workspace_ids,query_chars,results,files,returned_tokens,"
                + "source_file_tokens,took_ms,freshness").contains(",command,");
        assertThat(csv).doesNotContain("retrychargeflow");
        String md = webTestClient.get().uri("/vatstash/metrics/export?days=30&format=md").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/markdown")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(md).contains("| Calls |").contains("estimate");

        webTestClient.delete().uri("/vatstash/workspaces/{id}", id).exchange().expectStatus().isNoContent();
        webTestClient.get().uri("/vatstash/workspaces/{id}/status", id).exchange().expectStatus().isNotFound();
    }

    @Test
    void changesEndpointReindexesEditedFile() throws IOException {
        String id = register();
        awaitReady(id);
        assertThat(search(id, "freshlyadded").get("results")).isEmpty();

        write("src/main/java/demo/Ledger.java", "package demo;\n\npublic class Ledger {\n\n"
                + "    public int freshlyadded(int cents) {\n        int a = cents + 1;\n        int b = a * 2;\n"
                + "        int c = b - 3;\n        return c + 10;\n    }\n}\n");
        write("src/main/java/demo/Extra.java", "package demo;\n\npublic class Extra {\n\n"
                + "    public int extrabrandnew(int cents) {\n        int a = cents + 1;\n        int b = a * 2;\n"
                + "        int c = b - 3;\n        return c;\n    }\n}\n");
        Files.delete(root.resolve("web/client.ts"));

        webTestClient.post().uri("/vatstash/workspaces/{id}/changes", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "changed", List.of(root.resolve("src/main/java/demo/Ledger.java").toString(),
                                root.resolve("src/main/java/demo/Extra.java").toString(),
                                "/definitely/outside/Other.java"),
                        "deleted", List.of(root.resolve("web/client.ts").toString())))
                .exchange().expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.accepted").isEqualTo(3)
                .jsonPath("$.ignored").isEqualTo(1);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(search(id, "freshlyadded").get("results")).isNotEmpty();
            assertThat(search(id, "extrabrandnew").get("results")).isNotEmpty();
            assertThat(search(id, "fetchbalance").get("results")).isEmpty();
            assertThat(search(id, "postentry").get("results")).isEmpty();
        });

        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            tooMany.add(root.resolve("x" + i + ".java").toString());
        }
        webTestClient.post().uri("/vatstash/workspaces/{id}/changes", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("changed", tooMany))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_request");

        webTestClient.post().uri("/vatstash/workspaces/{id}/reindex", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("full", true))
                .exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.workspaceId").isEqualTo(id);
        awaitReady(id);
        assertThat(search(id, "freshlyadded").get("results")).isNotEmpty();
    }

    @Test
    void unknownWorkspaceIs404() {
        webTestClient.get().uri("/vatstash/workspaces/{id}/status", "ffffffffffffffff").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("workspace_not_found");
        webTestClient.delete().uri("/vatstash/workspaces/{id}", "ffffffffffffffff").exchange()
                .expectStatus().isNotFound();
        webTestClient.post().uri("/vatstash/workspaces/{id}/reindex", "ffffffffffffffff")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("full", false)).exchange()
                .expectStatus().isNotFound();
        webTestClient.post().uri("/vatstash/search").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "anything", "workspaceIds", List.of("ffffffffffffffff")))
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void invalidRootIs400() {
        for (String bad : new String[]{"relative/path", "/", System.getProperty("user.home"),
                "/definitely/not/a/real/dir"}) {
            webTestClient.post().uri("/vatstash/workspaces").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("root", bad)).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error").isEqualTo("invalid_root");
        }
        webTestClient.post().uri("/vatstash/workspaces").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").value(code ->
                        assertThat(code.toString()).isIn("invalid_request", "invalid_root"));
        webTestClient.post().uri("/vatstash/workspaces").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("not json").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_request");
    }

    @Test
    void unknownVatstashPathIs404NotForwarded() {
        int before = defaultUpstream.getRequestCount();

        webTestClient.get().uri("/vatstash/nope").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("not_found");
        webTestClient.post().uri("/vatstash/workspaces/abc/unknown").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("not_found");
        webTestClient.put().uri("/vatstash/workspaces").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange()
                .expectStatus().value(code -> assertThat(code).isIn(404, 405));
        webTestClient.get().uri("/mcp/extra").exchange().expectStatus().isNotFound();

        assertThat(defaultUpstream.getRequestCount()).isEqualTo(before);
    }

    @Test
    void mcpInitializeAndToolsCall() throws IOException {
        String id = register();
        awaitReady(id);

        WebTestClient.ResponseSpec init = webTestClient.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        JsonNode initBody = json(init);
        assertThat(initBody.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(initBody.at("/result/capabilities/tools").isObject()).isTrue();

        webTestClient.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        JsonNode tools = json(webTestClient.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .exchange().expectStatus().isOk());
        assertThat(tools.at("/result/tools/0/name").asText()).isEqualTo("search_code");

        Map<String, Object> call = Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "search_code",
                        "arguments", Map.of("query", "fetchbalance", "root", root.toString())));
        JsonNode result = json(webTestClient.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(call).exchange().expectStatus().isOk());
        assertThat(result.at("/result/isError").asBoolean()).isFalse();
        assertThat(result.at("/result/content/0/text").asText())
                .startsWith("[vatstash v1]").contains("web/client.ts");

        JsonNode viaParam = json(webTestClient.post().uri("/mcp?workspace={id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call",
                        "params", Map.of("name", "search_code", "arguments", Map.of("query", "postentry"))))
                .exchange().expectStatus().isOk());
        assertThat(viaParam.at("/result/content/0/text").asText()).contains("Ledger.java");

        JsonNode bad = json(webTestClient.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"nope\"}")
                .exchange().expectStatus().isOk());
        assertThat(bad.at("/error/code").asInt()).isEqualTo(-32601);
    }

    @Test
    void mcpGetIs405() {
        int before = defaultUpstream.getRequestCount();

        webTestClient.get().uri("/mcp").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().valueEquals("Allow", "POST");
        webTestClient.delete().uri("/mcp").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().valueEquals("Allow", "POST");

        assertThat(defaultUpstream.getRequestCount()).isEqualTo(before);
    }
}
