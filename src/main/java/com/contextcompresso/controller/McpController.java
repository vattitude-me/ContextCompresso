package com.contextcompresso.controller;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.vatstash.mcp.McpRequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

/**
 * MCP over streamable HTTP, JSON-response mode only: POST answers with application/json (or 202 for
 * notifications). Every other method on /mcp is 405 and any deeper path is 404, so none of it can
 * reach the generic passthrough to the upstream provider.
 */
@RestController
public class McpController {

    private final McpRequestHandler handler;
    private final VatStashProperties props;

    public McpController(McpRequestHandler handler, VatStashProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @PostMapping("/mcp")
    public Mono<ResponseEntity<Object>> post(@RequestBody(required = false) Mono<String> body,
                                             @RequestParam(name = "workspace", required = false) String workspace) {
        if (!props.enabled() || !props.mcpEnabled()) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body((Object) Map.of("error", "vatstash_disabled",
                            "message", "The VatStash MCP endpoint is disabled")));
        }
        return body.defaultIfEmpty("")
                .flatMap(raw -> Mono.fromCallable(() -> handler.handleRaw(raw, workspace))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(reply -> respond(reply));
    }

    private static ResponseEntity<Object> respond(Optional<JsonNode> reply) {
        if (reply.isEmpty()) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body((Object) reply.get());
    }

    @RequestMapping("/mcp")
    public Mono<ResponseEntity<Map<String, String>>> methodNotAllowed() {
        return Mono.just(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "POST")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "method_not_allowed", "message", "Use POST for the MCP endpoint")));
    }

    @RequestMapping("/mcp/**")
    public Mono<ResponseEntity<Map<String, String>>> notFound() {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "not_found", "message", "Unknown path; the MCP endpoint is POST /mcp")));
    }
}
