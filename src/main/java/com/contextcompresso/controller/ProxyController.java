package com.contextcompresso.controller;

import com.contextcompresso.compression.CompressionPipeline;
import com.contextcompresso.model.CompressedRequest;
import com.contextcompresso.provider.ProviderConfig;
import com.contextcompresso.provider.ProviderRouter;
import com.contextcompresso.usage.CachePrefixMonitor;
import com.contextcompresso.usage.SessionKeyResolver;
import com.contextcompresso.usage.UsageCapture;
import com.contextcompresso.usage.UsageExtractor;
import com.contextcompresso.usage.UsageRecord;
import com.contextcompresso.usage.UsageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(60);

    private final ProviderRouter providerRouter;
    private final CompressionPipeline compressionPipeline;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final UsageCapture usageCapture;
    private final UsageStore usageStore;
    private final CachePrefixMonitor cachePrefixMonitor;

    public ProxyController(ProviderRouter providerRouter,
                            CompressionPipeline compressionPipeline,
                            WebClient.Builder webClientBuilder,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry,
                            UsageCapture usageCapture,
                            UsageStore usageStore,
                            CachePrefixMonitor cachePrefixMonitor) {
        this.providerRouter = providerRouter;
        this.compressionPipeline = compressionPipeline;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.usageCapture = usageCapture;
        this.usageStore = usageStore;
        this.cachePrefixMonitor = cachePrefixMonitor;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<Flux<DataBuffer>>> chatCompletions(@RequestBody(required = false) Mono<String> rawBody,
                                                                    ServerHttpRequest request) {
        ProviderConfig config = providerRouter.resolve(request, "openai");
        return proxyWithCompression(rawBody, request, config, "/v1/chat/completions");
    }

    @PostMapping("/v1/messages")
    public Mono<ResponseEntity<Flux<DataBuffer>>> messages(@RequestBody(required = false) Mono<String> rawBody,
                                                              ServerHttpRequest request) {
        ProviderConfig config = providerRouter.resolve(request, "anthropic");
        return proxyWithCompression(rawBody, request, config, "/v1/messages");
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> proxyWithCompression(Mono<String> rawBody,
                                                                          ServerHttpRequest request,
                                                                          ProviderConfig config,
                                                                          String upstreamPath) {
        String requestId = UUID.randomUUID().toString();
        return rawBody.defaultIfEmpty("")
                .flatMap(body -> handleCompressedRequest(body, request, config, upstreamPath, requestId))
                .onErrorResume(e -> Mono.just(deserializationErrorResponse(e)));
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> handleCompressedRequest(String body, ServerHttpRequest request,
                                                                             ProviderConfig config,
                                                                             String upstreamPath, String requestId) {
        if (!body.isBlank()) {
            try {
                objectMapper.readTree(body);
            } catch (Exception e) {
                return Mono.just(deserializationErrorResponse(e));
            }
        }

        CompressedRequest compressed = compressionPipeline.compress(body, config, requestId);
        String outboundBody;
        try {
            outboundBody = objectMapper.writeValueAsString(compressed.body());
        } catch (Exception e) {
            outboundBody = body;
        }

        JsonNode requestRoot = parseQuietly(body);
        String sessionKey = requestRoot == null ? null : SessionKeyResolver.resolve(requestRoot);
        String provider = config.name().name();
        meterRegistry.counter("cc.requests.total", "provider", provider, "status", "attempted").increment();

        if (config.name() == com.contextcompresso.provider.Provider.CLAUDE && requestRoot != null) {
            CachePrefixMonitor.PrefixCheck prefixCheck = cachePrefixMonitor.check(sessionKey, requestRoot);
            if (prefixCheck.diverged()) {
                meterRegistry.counter("cc.cache.prefix.diverged", "provider", provider).increment();
            }
        }

        WebClient client = webClientBuilder.build();
        WebClient.RequestBodySpec requestSpec = client.post()
                .uri(config.baseUrl() + upstreamPath);

        for (String headerName : config.forwardHeaders()) {
            List<String> values = request.getHeaders().get(headerName);
            if (values != null) {
                for (String value : values) {
                    requestSpec = requestSpec.header(headerName, value);
                }
            }
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        boolean streaming = compressed.streaming();

        return requestSpec
                .bodyValue(outboundBody)
                .retrieve()
                .toEntityFlux(DataBuffer.class)
                .onErrorResume(WebClientResponseException.class,
                        e -> Mono.just(ResponseEntity.status(e.getStatusCode())
                                .headers(h -> h.putAll(e.getHeaders()))
                                .body(Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                                        .wrap(e.getResponseBodyAsByteArray())))))
                .map(upstreamResponse -> {
                    long elapsedMs = sample.stop(meterRegistry.timer("cc.upstream.duration",
                            "provider", provider, "streaming", String.valueOf(streaming))) / 1_000_000;

                    HttpStatus status = HttpStatus.resolve(upstreamResponse.getStatusCode().value());
                    HttpStatus resolvedStatus = status != null ? status : HttpStatus.BAD_GATEWAY;

                    ResponseEntity.BodyBuilder builder = ResponseEntity.status(resolvedStatus)
                            .header("X-CC-Original-Chars", String.valueOf(compressed.stats().originalChars()))
                            .header("X-CC-Compressed-Chars", String.valueOf(compressed.stats().compressedChars()))
                            .header("X-CC-Ratio", String.valueOf(compressed.stats().ratio()))
                            .header("X-CC-Request-Id", requestId);

                    if (streaming) {
                        builder = builder.contentType(MediaType.TEXT_EVENT_STREAM);
                    } else {
                        MediaType upstreamContentType = upstreamResponse.getHeaders().getContentType() != null
                                ? upstreamResponse.getHeaders().getContentType() : MediaType.APPLICATION_JSON;
                        builder = builder.contentType(upstreamContentType);
                    }

                    meterRegistry.counter("cc.requests.total", "provider", provider,
                            "status", String.valueOf(resolvedStatus.value())).increment();

                    Flux<DataBuffer> upstreamBody = upstreamResponse.getBody() != null
                            ? upstreamResponse.getBody() : Flux.empty();
                    Flux<DataBuffer> responseBody = usageCapture.tap(upstreamBody, streaming,
                            usage -> recordUsage(requestId, sessionKey, provider, usage, compressed, elapsedMs));
                    return builder.body(responseBody);
                })
                .timeout(UPSTREAM_TIMEOUT)
                .onErrorResume(e -> e instanceof TimeoutException || e instanceof java.util.concurrent.TimeoutException,
                        e -> Mono.just(timeoutErrorResponse(requestId)))
                .onErrorResume(e -> {
                    log.error("Upstream call failed for requestId={}", requestId, e);
                    return Mono.just(timeoutErrorResponse(requestId));
                });
    }

    private JsonNode parseQuietly(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return null;
        }
    }

    private void recordUsage(String requestId, String sessionKey, String provider,
                              UsageExtractor.ExtractedUsage usage, CompressedRequest compressed, long elapsedMs) {
        if (usage.isEmpty()) {
            return;
        }
        UsageRecord record = new UsageRecord(
                requestId, sessionKey, provider, usage.model(),
                usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(),
                compressed.stats().originalChars(), compressed.stats().compressedChars(),
                elapsedMs, System.currentTimeMillis());

        Mono.fromRunnable(() -> {
                    try {
                        usageStore.store(record);
                    } catch (Exception e) {
                        log.debug("Failed to persist usage record for requestId={} (fail-open)", requestId, e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private ResponseEntity<Flux<DataBuffer>> timeoutErrorResponse(String requestId) {
        String json = "{\"error\":\"upstream_timeout\",\"requestId\":\"" + requestId + "\"}";
        DataBuffer buffer = new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                .wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-CC-Request-Id", requestId)
                .body(Flux.just(buffer));
    }

    private ResponseEntity<Flux<DataBuffer>> deserializationErrorResponse(Throwable e) {
        String message = e.getMessage() == null ? "invalid request body" : e.getMessage().replace("\"", "'");
        String json = "{\"error\":\"deserialization_failed\",\"message\":\"" + message + "\"}";
        DataBuffer buffer = new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                .wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(buffer));
    }

    // Generic pass-through (no compression): forwards any other path to the DEFAULT
    // provider's upstream unchanged, streaming the body through without buffering.
    @RequestMapping("/**")
    public Mono<ResponseEntity<Flux<DataBuffer>>> genericProxy(ServerHttpRequest request,
                                                                 @RequestBody(required = false) Flux<DataBuffer> body) {
        ProviderConfig config = providerRouter.toConfig(com.contextcompresso.provider.Provider.DEFAULT);
        String path = request.getPath().value();
        WebClient client = webClientBuilder.build();
        WebClient.RequestBodySpec requestSpec = client.method(request.getMethod())
                .uri(config.baseUrl() + path);

        for (String headerName : config.forwardHeaders()) {
            List<String> values = request.getHeaders().get(headerName);
            if (values != null) {
                for (String value : values) {
                    requestSpec = requestSpec.header(headerName, value);
                }
            }
        }

        return requestSpec
                .body((body == null ? Flux.<DataBuffer>empty() : body), DataBuffer.class)
                .retrieve()
                .toEntityFlux(DataBuffer.class)
                .onErrorResume(WebClientResponseException.class,
                        e -> Mono.just(ResponseEntity.status(e.getStatusCode())
                                .headers(h -> h.putAll(e.getHeaders()))
                                .body(Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                                        .wrap(e.getResponseBodyAsByteArray())))))
                .map(upstreamResponse -> {
                    HttpStatus status = HttpStatus.resolve(upstreamResponse.getStatusCode().value());
                    HttpStatus resolvedStatus = status != null ? status : HttpStatus.BAD_GATEWAY;
                    MediaType upstreamContentType = upstreamResponse.getHeaders().getContentType() != null
                            ? upstreamResponse.getHeaders().getContentType() : MediaType.APPLICATION_OCTET_STREAM;
                    Flux<DataBuffer> responseBody = upstreamResponse.getBody() != null
                            ? upstreamResponse.getBody() : Flux.empty();
                    return ResponseEntity.status(resolvedStatus)
                            .contentType(upstreamContentType)
                            .body(responseBody);
                })
                .timeout(UPSTREAM_TIMEOUT)
                .onErrorResume(e -> {
                    log.error("Generic passthrough failed for path={}", path, e);
                    return Mono.just(timeoutErrorResponse(UUID.randomUUID().toString()));
                });
    }
}
