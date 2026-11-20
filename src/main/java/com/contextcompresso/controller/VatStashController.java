package com.contextcompresso.controller;

import com.contextcompresso.vatstash.VatStashException;
import com.contextcompresso.vatstash.VatStashService;
import com.contextcompresso.vatstash.index.IndexStatus;
import com.contextcompresso.vatstash.metrics.MetricsSummary;
import com.contextcompresso.vatstash.metrics.VatStashMetricsService;
import com.contextcompresso.vatstash.search.SearchRequest;
import com.contextcompresso.vatstash.search.SearchResponse;
import com.contextcompresso.vatstash.workspace.WorkspaceIndexer;
import com.contextcompresso.vatstash.workspace.WorkspaceInfo;
import com.contextcompresso.vatstash.workspace.WorkspaceSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** HTTP API of VatStash (the contract in section 3.0 of the plan). */
@RestController
@RequestMapping("/vatstash")
public class VatStashController {

    private static final Logger log = LoggerFactory.getLogger(VatStashController.class);
    private static final MediaType TEXT_CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
    private static final MediaType TEXT_MARKDOWN =
            new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8);

    private final VatStashService service;
    private final VatStashMetricsService metrics;
    private final ObjectMapper mapper;

    public VatStashController(VatStashService service, VatStashMetricsService metrics, ObjectMapper mapper) {
        this.service = service;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @PostMapping("/workspaces")
    public Mono<ResponseEntity<WorkspaceInfo>> register(@RequestBody(required = false) Mono<String> body) {
        return body.defaultIfEmpty("").flatMap(raw -> blocking(() -> {
            JsonNode json = parseObject(raw);
            String root = text(json, "root");
            WorkspaceSettings settings = settings(json.get("settings"));
            return ResponseEntity.ok(service.register(root, settings));
        }));
    }

    @GetMapping("/workspaces")
    public Mono<ResponseEntity<List<WorkspaceInfo>>> list() {
        return blocking(() -> ResponseEntity.ok(service.list()));
    }

    @GetMapping("/workspaces/{id}/status")
    public Mono<ResponseEntity<IndexStatus>> status(@PathVariable String id) {
        return blocking(() -> ResponseEntity.ok(service.status(id)));
    }

    @PostMapping("/workspaces/{id}/changes")
    public Mono<ResponseEntity<Map<String, Integer>>> changes(@PathVariable String id,
                                                              @RequestBody(required = false) Mono<String> body) {
        return body.defaultIfEmpty("").flatMap(raw -> blocking(() -> {
            JsonNode json = parseObject(raw);
            List<String> changed = stringList(json, "changed");
            List<String> deleted = stringList(json, "deleted");
            WorkspaceIndexer.ChangeResult r = service.applyChanges(id, changed, deleted);
            Map<String, Integer> out = new LinkedHashMap<>();
            out.put("accepted", r.accepted());
            out.put("ignored", r.ignored());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
        }));
    }

    @PostMapping("/workspaces/{id}/reindex")
    public Mono<ResponseEntity<IndexStatus>> reindex(@PathVariable String id,
                                                     @RequestBody(required = false) Mono<String> body) {
        return body.defaultIfEmpty("").flatMap(raw -> blocking(() -> {
            boolean full = false;
            if (!raw.isBlank()) {
                JsonNode json = parseObject(raw);
                JsonNode f = json.get("full");
                if (f != null && !f.isNull()) {
                    if (!f.isBoolean()) {
                        throw VatStashException.invalidRequest("full must be a boolean");
                    }
                    full = f.asBoolean();
                }
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.reindex(id, full));
        }));
    }

    @DeleteMapping("/workspaces/{id}")
    public Mono<ResponseEntity<Void>> remove(@PathVariable String id) {
        return blocking(() -> {
            service.remove(id);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/search")
    public Mono<ResponseEntity<SearchResponse>> search(@RequestBody(required = false) Mono<String> body) {
        return body.defaultIfEmpty("").flatMap(raw -> blocking(() -> {
            SearchRequest request;
            try {
                request = mapper.readValue(raw.isBlank() ? "{}" : raw, SearchRequest.class);
            } catch (Exception e) {
                throw VatStashException.invalidRequest("request body is not valid: " + shortMessage(e));
            }
            return ResponseEntity.ok(service.search(request));
        }));
    }

    @GetMapping("/metrics/summary")
    public Mono<ResponseEntity<MetricsSummary>> summary(@RequestParam(name = "days", defaultValue = "7") int days) {
        return blocking(() -> {
            requireEnabled();
            return ResponseEntity.ok(metrics.summary(days));
        });
    }

    @GetMapping("/metrics/export")
    public Mono<ResponseEntity<String>> export(@RequestParam(name = "days", defaultValue = "30") int days,
                                               @RequestParam(name = "format", defaultValue = "csv") String format) {
        return blocking(() -> {
            requireEnabled();
            if ("csv".equalsIgnoreCase(format)) {
                return ResponseEntity.ok().contentType(TEXT_CSV).body(metrics.exportCsv(days));
            }
            if ("md".equalsIgnoreCase(format) || "markdown".equalsIgnoreCase(format)) {
                return ResponseEntity.ok().contentType(TEXT_MARKDOWN).body(metrics.exportMarkdown(days));
            }
            throw VatStashException.invalidRequest("format must be csv or md");
        });
    }

    /** Anything else under /vatstash is a 404 here, so it can never reach the generic passthrough. */
    @RequestMapping("/**")
    public Mono<ResponseEntity<Map<String, String>>> notFound(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(error("not_found", "Unknown VatStash path: " + path)));
    }

    @ExceptionHandler(VatStashException.class)
    public ResponseEntity<Map<String, String>> onVatStashException(VatStashException e) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(error(e.code(), e.getMessage()), headers, e.status());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onUnexpected(Exception e) {
        log.warn("VatStash request failed: {}", e.getMessage(), e);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(error("vatstash_unavailable", "VatStash failed: " + shortMessage(e)), headers,
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ---- helpers ----

    private <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    private void requireEnabled() {
        if (!service.isEnabled()) {
            throw VatStashException.disabled();
        }
    }

    private JsonNode parseObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw VatStashException.invalidRequest("a JSON body is required");
        }
        try {
            JsonNode node = mapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw VatStashException.invalidRequest("the body must be a JSON object");
            }
            return node;
        } catch (VatStashException e) {
            throw e;
        } catch (Exception e) {
            throw VatStashException.invalidRequest("the body is not valid JSON: " + shortMessage(e));
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode n = json.get(field);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw VatStashException.invalidRequest(field + " is required");
        }
        return n.asText();
    }

    private WorkspaceSettings settings(JsonNode n) {
        if (n == null || n.isNull()) {
            return WorkspaceSettings.empty();
        }
        if (!n.isObject()) {
            throw VatStashException.invalidRequest("settings must be an object");
        }
        try {
            return mapper.treeToValue(n, WorkspaceSettings.class);
        } catch (Exception e) {
            throw VatStashException.invalidRequest("settings is not valid: " + shortMessage(e));
        }
    }

    private static List<String> stringList(JsonNode json, String field) {
        JsonNode n = json.get(field);
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw VatStashException.invalidRequest(field + " must be an array of absolute paths");
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode e : n) {
            if (!e.isTextual()) {
                throw VatStashException.invalidRequest(field + " must be an array of absolute paths");
            }
            out.add(e.asText());
        }
        return out;
    }

    private static Map<String, String> error(String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }

    private static String shortMessage(Throwable e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
