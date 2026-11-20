package com.contextcompresso.vatstash.mcp;

import com.contextcompresso.vatstash.VatStashTestSupport;
import com.contextcompresso.vatstash.VatStashTestSupport.Env;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.contextcompresso.vatstash.VatStashTestSupport.write;
import static org.assertj.core.api.Assertions.assertThat;

class McpRequestHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tmp;

    Env env;
    McpRequestHandler handler;
    Path rootOne;

    @BeforeEach
    void setUp() throws IOException {
        rootOne = Files.createDirectory(tmp.resolve("one")).toRealPath();
        write(rootOne, "src/Pay.java", "public class Pay {\n    public int retryCharge(int cents) {\n"
                + "        int a = cents + 1;\n        int b = a * 2;\n        int c = b - 3;\n        return c;\n    }\n}\n");
        env = VatStashTestSupport.env(tmp);
        handler = new McpRequestHandler(env.service(), env.props(), mapper);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private JsonNode call(String json) throws IOException {
        Optional<JsonNode> out = handler.handleRaw(json, null);
        assertThat(out).isPresent();
        return out.get();
    }

    @Test
    void initializeEchoesSupportedProtocolVersion() throws IOException {
        JsonNode newer = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\"}}}");
        assertThat(newer.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(newer.get("id").asInt()).isEqualTo(1);
        assertThat(newer.at("/result/protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(newer.at("/result/capabilities/tools").isObject()).isTrue();
        assertThat(newer.at("/result/serverInfo/name").asText()).isNotBlank();

        JsonNode current = call("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\"}}");
        assertThat(current.get("id").asText()).isEqualTo("a");
        assertThat(current.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");

        JsonNode unknown = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"1999-01-01\"}}");
        assertThat(unknown.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");

        JsonNode ping = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
        assertThat(ping.get("result").isObject()).isTrue();
    }

    @Test
    void notificationReturnsEmpty() {
        assertThat(handler.handleRaw("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null)).isEmpty();
        assertThat(handler.handleRaw("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                + "\"params\":{\"requestId\":1}}", null)).isEmpty();
    }

    @Test
    void toolsListExposesSearchCodeSchema() throws IOException {
        JsonNode res = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}");

        JsonNode tools = res.at("/result/tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools).hasSize(1);
        JsonNode tool = tools.get(0);
        assertThat(tool.get("name").asText()).isEqualTo("search_code");
        assertThat(tool.get("description").asText()).isNotBlank();
        assertThat(tool.at("/inputSchema/type").asText()).isEqualTo("object");
        assertThat(tool.at("/inputSchema/required").get(0).asText()).isEqualTo("query");
        assertThat(tool.at("/inputSchema/properties/query/type").asText()).isEqualTo("string");
        assertThat(tool.at("/inputSchema/properties/root/type").asText()).isEqualTo("string");
        assertThat(tool.at("/inputSchema/properties/pathPrefix/type").asText()).isEqualTo("string");
        assertThat(tool.at("/inputSchema/properties/languages/type").asText()).isEqualTo("array");
        assertThat(tool.at("/inputSchema/properties/maxResults/type").asText()).isEqualTo("integer");
    }

    @Test
    void toolsCallReturnsTextContent() throws IOException {
        env.registerAndIndex(rootOne);

        JsonNode res = call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"search_code\","
                + "\"arguments\":{\"query\":\"retryCharge\",\"root\":" + mapper.writeValueAsString(rootOne.toString())
                + "}}}");

        assertThat(res.has("error")).isFalse();
        JsonNode result = res.get("result");
        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.at("/content/0/type").asText()).isEqualTo("text");
        String text = result.at("/content/0/text").asText();
        assertThat(text).startsWith("[vatstash v1]").contains("src/Pay.java").contains("retryCharge");

        JsonNode noQuery = call("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"search_code\",\"arguments\":{}}}");
        assertThat(noQuery.at("/error/code").asInt()).isEqualTo(-32602);

        JsonNode badTool = call("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"nope\",\"arguments\":{}}}");
        assertThat(badTool.at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    void ambiguousRootReturnsIsError() throws IOException {
        Path rootTwo = Files.createDirectory(tmp.resolve("two")).toRealPath();
        write(rootTwo, "B.java", "public class B {}\n");
        env.registerAndIndex(rootOne);
        env.registerAndIndex(rootTwo);

        JsonNode res = call("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"search_code\",\"arguments\":{\"query\":\"anything\"}}}");

        assertThat(res.has("error")).isFalse();
        assertThat(res.at("/result/isError").asBoolean()).isTrue();
        String text = res.at("/result/content/0/text").asText();
        assertThat(text).contains(rootOne.toString()).contains(rootTwo.toString()).contains("root");

        JsonNode byParam = handler.handleRaw("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"search_code\",\"arguments\":{\"query\":\"retryCharge\"}}}", rootOne.toString()).orElseThrow();
        assertThat(byParam.at("/result/isError").asBoolean()).isFalse();
        assertThat(byParam.at("/result/content/0/text").asText()).contains("src/Pay.java");
    }

    @Test
    void unknownMethodIsMethodNotFound() throws IOException {
        JsonNode res = call("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"resources/list\"}");

        assertThat(res.at("/error/code").asInt()).isEqualTo(-32601);
        assertThat(res.get("id").asInt()).isEqualTo(20);
        assertThat(res.has("result")).isFalse();

        JsonNode garbage = handler.handleRaw("{not json", null).orElseThrow();
        assertThat(garbage.at("/error/code").asInt()).isEqualTo(-32700);
        assertThat(garbage.get("id").isNull()).isTrue();
    }

    @Test
    void batchArrayRejected() throws IOException {
        JsonNode res = call("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]");

        assertThat(res.at("/error/code").asInt()).isEqualTo(-32600);
        assertThat(res.get("id").isNull()).isTrue();
    }
}
