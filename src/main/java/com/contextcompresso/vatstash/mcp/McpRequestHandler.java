package com.contextcompresso.vatstash.mcp;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.vatstash.VatStashException;
import com.contextcompresso.vatstash.VatStashService;
import com.contextcompresso.vatstash.search.SearchRequest;
import com.contextcompresso.vatstash.search.SearchResponse;
import com.contextcompresso.vatstash.workspace.WorkspaceInfo;
import com.contextcompresso.vatstash.workspace.WorkspaceRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Minimal MCP server for streamable HTTP in JSON-response mode: stateless JSON-RPC 2.0 over POST.
 * It serves a single tool, {@code search_code}. Pure Java so it can be tested without a web stack.
 */
@Component
public class McpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(McpRequestHandler.class);

    static final String TOOL_NAME = "search_code";
    static final String DEFAULT_PROTOCOL = "2025-06-18";
    static final String MIN_PROTOCOL = "2025-03-26";
    private static final Pattern PROTOCOL_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final String TOOL_DESCRIPTION = "Search the local code index of the current project (BM25 over symbols, "
            + "file paths and source text). Use identifiers, class or method names and a few keywords rather than "
            + "full sentences. Returns snippets with file paths and line ranges, so prefer it to reading whole "
            + "files. If nothing matches, fall back to your normal search tools.";
    static final String INSTRUCTIONS =
            "Search the local code index before reading whole files. Pass root = your working directory.";

    private final VatStashService service;
    private final VatStashProperties props;
    private final ObjectMapper mapper;

    public McpRequestHandler(VatStashService service, VatStashProperties props, ObjectMapper mapper) {
        this.service = service;
        this.props = props;
        this.mapper = mapper;
    }

    /** Parses a raw request body. A parse error is answered with -32700. */
    public Optional<JsonNode> handleRaw(String body, String workspaceParam) {
        JsonNode message;
        try {
            message = mapper.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            return Optional.of(error(null, -32700, "Parse error"));
        }
        if (message == null || message.isMissingNode() || message.isNull()) {
            return Optional.of(error(null, -32700, "Parse error"));
        }
        return handle(message, workspaceParam);
    }

    public Optional<JsonNode> handle(JsonNode message) {
        return handle(message, null);
    }

    /** Returns empty for notifications, which are answered with 202 and no body. */
    public Optional<JsonNode> handle(JsonNode message, String workspaceParam) {
        if (message.isArray()) {
            return Optional.of(error(null, -32600, "Batch requests are not supported"));
        }
        if (!message.isObject()) {
            return Optional.of(error(null, -32600, "Invalid Request"));
        }
        JsonNode id = message.get("id");
        boolean notification = id == null || id.isNull();
        JsonNode methodNode = message.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            // a response object or garbage: nothing to answer
            return notification ? Optional.empty() : Optional.of(error(id, -32600, "Invalid Request"));
        }
        if (notification) {
            return Optional.empty();
        }
        String method = methodNode.asText();
        JsonNode params = message.get("params");
        try {
            return switch (method) {
                case "initialize" -> Optional.of(result(id, initialize(params)));
                case "ping" -> Optional.of(result(id, mapper.createObjectNode()));
                case "tools/list" -> Optional.of(result(id, toolsList()));
                case "tools/call" -> toolsCall(id, params, workspaceParam);
                default -> Optional.of(error(id, -32601, "Method not found: " + method));
            };
        } catch (RuntimeException e) {
            log.warn("MCP request {} failed: {}", method, e.getMessage(), e);
            return Optional.of(error(id, -32603, "Internal error"));
        }
    }

    // ---- methods ----

    private ObjectNode initialize(JsonNode params) {
        String requested = params != null && params.hasNonNull("protocolVersion")
                ? params.get("protocolVersion").asText() : null;
        String version = DEFAULT_PROTOCOL;
        if (requested != null && PROTOCOL_PATTERN.matcher(requested).matches()
                && requested.compareTo(MIN_PROTOCOL) >= 0) {
            version = requested;
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", version);
        ObjectNode tools = result.putObject("capabilities").putObject("tools");
        tools.put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "contextcompresso-vatstash");
        String impl = McpRequestHandler.class.getPackage().getImplementationVersion();
        info.put("version", impl == null ? "dev" : impl);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", TOOL_DESCRIPTION);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string")
                .put("description", "Identifiers or keywords to search for");
        props.putObject("root").put("type", "string")
                .put("description", "Absolute path of the project you are working in (your cwd)");
        props.putObject("pathPrefix").put("type", "string")
                .put("description", "Only search files under this path, relative to the project root");
        ObjectNode languages = props.putObject("languages");
        languages.put("type", "array");
        languages.putObject("items").put("type", "string");
        ObjectNode max = props.putObject("maxResults");
        max.put("type", "integer");
        max.put("minimum", 1);
        max.put("maximum", 20);
        schema.putArray("required").add("query");
        return result;
    }

    private Optional<JsonNode> toolsCall(JsonNode id, JsonNode params, String workspaceParam) {
        if (params == null || !params.isObject() || !params.hasNonNull("name")) {
            return Optional.of(error(id, -32602, "Invalid params: name is required"));
        }
        String name = params.get("name").asText();
        if (!TOOL_NAME.equals(name)) {
            return Optional.of(error(id, -32602, "Unknown tool: " + name));
        }
        JsonNode args = params.get("arguments");
        if (args == null || args.isNull()) {
            args = mapper.createObjectNode();
        }
        if (!args.isObject()) {
            return Optional.of(error(id, -32602, "Invalid params: arguments must be an object"));
        }
        JsonNode query = args.get("query");
        if (query == null || !query.isTextual() || query.asText().isBlank()) {
            return Optional.of(error(id, -32602, "Invalid params: query is required and must be a string"));
        }
        String root = optText(args, "root");
        String pathPrefix = optText(args, "pathPrefix");
        if (args.has("root") && !args.get("root").isNull() && !args.get("root").isTextual()
                || args.has("pathPrefix") && !args.get("pathPrefix").isNull() && !args.get("pathPrefix").isTextual()) {
            return Optional.of(error(id, -32602, "Invalid params: root and pathPrefix must be strings"));
        }
        List<String> languages = null;
        JsonNode langNode = args.get("languages");
        if (langNode != null && !langNode.isNull()) {
            if (!langNode.isArray()) {
                return Optional.of(error(id, -32602, "Invalid params: languages must be an array of strings"));
            }
            languages = new ArrayList<>();
            for (JsonNode l : langNode) {
                if (!l.isTextual()) {
                    return Optional.of(error(id, -32602, "Invalid params: languages must be an array of strings"));
                }
                languages.add(l.asText());
            }
        }
        Integer maxResults = null;
        JsonNode maxNode = args.get("maxResults");
        if (maxNode != null && !maxNode.isNull()) {
            if (!maxNode.isIntegralNumber()) {
                return Optional.of(error(id, -32602, "Invalid params: maxResults must be an integer"));
            }
            maxResults = Math.max(1, Math.min(20, maxNode.asInt()));
        }

        try {
            List<String> workspaceIds = resolveWorkspace(root, workspaceParam);
            SearchRequest request = new SearchRequest(query.asText(), workspaceIds, null, maxResults, null,
                    pathPrefix, languages, "mcp");
            SearchResponse response = service.search(request);
            return Optional.of(result(id, toolResult(response.text(), false)));
        } catch (ToolFailure e) {
            return Optional.of(result(id, toolResult(e.getMessage(), true)));
        } catch (VatStashException e) {
            return Optional.of(result(id, toolResult("VatStash: " + e.getMessage(), true)));
        } catch (RuntimeException e) {
            log.warn("MCP search_code failed: {}", e.getMessage(), e);
            return Optional.of(result(id, toolResult("VatStash search failed: " + e.getMessage(), true)));
        }
    }

    /** Root argument, then the ?workspace= parameter, then the only registered workspace. */
    private List<String> resolveWorkspace(String root, String workspaceParam) {
        if (root != null && !root.isBlank()) {
            Optional<WorkspaceRecord> ws = service.resolveByPath(root);
            if (ws.isPresent()) {
                return List.of(ws.get().workspaceId());
            }
            if (props.mcpAutoRegister()) {
                WorkspaceInfo info = service.register(root, null);
                return List.of(info.workspaceId());
            }
            throw new ToolFailure("No workspace is registered for " + root + ". " + registeredRoots());
        }
        if (workspaceParam != null && !workspaceParam.isBlank()) {
            Optional<WorkspaceRecord> byId = service.findWorkspace(workspaceParam);
            if (byId.isPresent()) {
                return List.of(byId.get().workspaceId());
            }
            Optional<WorkspaceRecord> byPath = service.resolveByPath(workspaceParam);
            if (byPath.isPresent()) {
                return List.of(byPath.get().workspaceId());
            }
            throw new ToolFailure("The workspace " + workspaceParam + " is not registered. " + registeredRoots());
        }
        List<WorkspaceRecord> all = service.registeredWorkspaces();
        if (all.size() == 1) {
            return List.of(all.get(0).workspaceId());
        }
        throw new ToolFailure("Pass root = the absolute path of your project. " + registeredRoots());
    }

    private String registeredRoots() {
        List<WorkspaceRecord> all = service.registeredWorkspaces();
        if (all.isEmpty()) {
            return "No workspaces are registered yet.";
        }
        StringBuilder sb = new StringBuilder("Registered roots:");
        for (WorkspaceRecord ws : all) {
            sb.append("\n- ").append(ws.root());
        }
        return sb.toString();
    }

    private ObjectNode toolResult(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        return result;
    }

    private static String optText(JsonNode args, String field) {
        JsonNode n = args.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    // ---- JSON-RPC envelopes ----

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id);
        out.set("result", result);
        return out;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        if (id == null) {
            out.putNull("id");
        } else {
            out.set("id", id);
        }
        ObjectNode err = out.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return out;
    }

    private static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) {
            super(message);
        }
    }
}
