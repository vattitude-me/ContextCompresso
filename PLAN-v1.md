# ContextCompresso — Implementation Plan v1

## Project Overview

ContextCompresso is a reactive Spring Boot proxy that sits between AI-powered IDE tools and LLM APIs. It intercepts requests, compresses message payloads to reduce token costs and latency, stores originals in SQLite for retrieval, and forwards compressed requests upstream.

**Problem:** LLM API calls from developer tools (GitHub Copilot, Claude Code) often include redundant content — verbose tool results, commented code blocks, repeated JSON structures, and oversized file dumps. This inflates token usage (and cost) without improving response quality.

**Solution:** A transparent compression proxy that developers point their tools at. No code changes to the IDE or CLI — just a config change to route traffic through ContextCompresso.

**Primary supported providers:**
- **GitHub Copilot** (Copilot Chat API / OpenAI-compatible)
- **Claude Code** (Anthropic Messages API)
- **Generic OpenAI-compatible endpoints** (fallback for any provider)

---

## Data Flow

```
Client (VS Code / JetBrains / CLI)
    |
    | POST /v1/chat/completions  OR  /v1/messages
    v
ProviderRouter
    |── detect provider from:
    |     1. X-CC-Provider header (explicit override)
    |     2. Authorization header format (Bearer ghp_ = Copilot)
    |     3. Request path (/v1/messages = Anthropic)
    |     4. Configured default
    |
    v
CompressionPipeline (provider-aware)
    |── [1] CacheAligner (provider-specific ordering logic)
    |── [2] SmartCrusher (structural JSON cleanup)
    |── [3] CodeCompressor (strip comments, collapse blanks)
    |── [4] TextTruncator (head+tail with sentinel for long content)
    |── [5] CcrStore (store originals in SQLite, inject reference IDs)
    |
    v
WebClient  ──>  Provider API (Copilot / Anthropic / OpenAI)
                  (Authorization headers forwarded unchanged)
    |
    v
Response (streaming SSE or JSON)
    |
    v
ProxyController  ──>  Client
    (adds X-CC-Ratio, X-CC-Request-Id headers)
```

---

## Architecture Decisions

### Why WebFlux (Reactive)?

Streaming LLM responses (SSE) require non-blocking I/O. WebFlux pipes SSE chunks directly from upstream to the client without buffering, keeping memory flat regardless of response length.

### Why Multi-Provider Config?

Copilot and Claude have fundamentally different API contracts, auth flows, and caching semantics. A single `target-url` forces the proxy to guess. Explicit provider configs make behavior correct by construction.

### Why NOT Reorder Messages for Claude?

Anthropic's prompt caching matches on exact byte prefix. Reordering system messages destroys cache hits and *increases* cost. The CacheAligner for Claude focuses on deterministic compression output instead.

### Why jtokkit for Token Estimation?

The naive `chars/4` heuristic is 15-30% off for code-heavy content. jtokkit gives exact GPT-4 token counts in <1ms. For Claude, we use a calibrated char-ratio (chars/3.2 for English, chars/2.5 for code) until Anthropic publishes a Java tokenizer.

### Why SQLite?

ContextCompresso is a local/sidecar proxy. SQLite with WAL mode handles concurrent reads and the single-writer workload without requiring a separate database process.

### Why Fail-Open on Compression Errors?

A compression bug must never block a user request. The pipeline wraps in a try-catch that falls back to the original request body. This is non-negotiable.

### Why Request Deduplication?

IDE integrations (especially Copilot) can fire duplicate requests on rapid keystrokes or retry logic. Deduping within a 2s window prevents double-billing and double-latency.

---

## Provider Integration

### GitHub Copilot

**How it connects:** Copilot Chat (VS Code / JetBrains) sends requests to `https://api.githubcopilot.com/chat/completions`.

**Client configuration:**
```json
{
  "github.copilot.advanced": {
    "debug.overrideProxyUrl": "http://localhost:8080"
  }
}
```

**Auth:** Copilot uses `Authorization: Bearer ghp_...` or a short-lived Copilot token. Forwarded unchanged.

**Constraints:**
- Copilot injects system prompts that MUST NOT be compressed (behavioral guardrails)
- Function calling (`tool_calls`, `functions` arrays) must be preserved exactly
- File snippets include line number prefixes — CodeCompressor must preserve these
- Detection: `Authorization: Bearer ghp_*` or presence of `Copilot-Integration-Id` header

**Compression strategy:**
- Aggressive code comment stripping (contexts are code-heavy)
- Skip compression on messages with Copilot system prompt signatures
- Preserve tool call structures verbatim

---

### Claude Code

**How it connects:** Claude Code (CLI / VS Code extension) talks to `https://api.anthropic.com/v1/messages`.

**Client configuration:**
```bash
export ANTHROPIC_BASE_URL=http://localhost:8080
```

**Auth:** `X-Api-Key: sk-ant-...`. Forwarded unchanged.

**Constraints:**
- `cache_control: {type: "ephemeral"}` on system messages must be preserved exactly
- Tool results (file contents, grep output) are the prime compression target — often 10-50KB
- Extended thinking (`thinking` blocks in responses) pass through without modification
- Anthropic's prompt caching is prefix-based: system prompt + first N messages must be byte-identical across requests for cache hits
- CacheAligner must NOT reorder messages for Claude

**Compression strategy:**
- Focus on TextTruncator for large tool results
- Deterministic compression (same input = same output) to preserve cache hits
- Never modify `cache_control` metadata blocks

---

### Provider Detection

```java
public Provider detect(ServerHttpRequest request) {
    // 1. Explicit override always wins
    String explicit = request.getHeaders().getFirst("X-CC-Provider");
    if (explicit != null) return Provider.valueOf(explicit.toUpperCase());

    // 2. Auth header heuristics
    String auth = request.getHeaders().getFirst("Authorization");
    if (auth != null && auth.startsWith("Bearer ghp_")) return Provider.COPILOT;
    if (request.getHeaders().containsKey("Copilot-Integration-Id")) return Provider.COPILOT;

    String apiKey = request.getHeaders().getFirst("X-Api-Key");
    if (apiKey != null && apiKey.startsWith("sk-ant-")) return Provider.CLAUDE;

    // 3. Path-based
    if (request.getPath().toString().contains("/v1/messages")) return Provider.CLAUDE;

    return Provider.DEFAULT;
}
```

---

## Configuration Reference

```yaml
server:
  port: 8080

contextcompresso:
  providers:
    copilot:
      enabled: true
      base-url: https://api.githubcopilot.com
      api-style: openai
      auth-type: github-token
      max-context-tokens: 128000
      token-estimator: jtokkit
      forward-headers:
        - Authorization
        - X-GitHub-Token
        - Editor-Version
        - Copilot-Integration-Id
      compression:
        code-compression-enabled: true
        strip-copilot-system-prompts: false
        aggressive-code-strip: true

    claude:
      enabled: true
      base-url: https://api.anthropic.com
      api-style: anthropic
      auth-type: api-key
      max-context-tokens: 200000
      token-estimator: anthropic
      forward-headers:
        - X-Api-Key
        - Anthropic-Version
        - Anthropic-Beta
      compression:
        code-compression-enabled: true
        cache-alignment-enabled: true
        preserve-cache-control-blocks: true

    default:
      enabled: true
      base-url: https://api.openai.com
      api-style: openai
      auth-type: api-key
      max-context-tokens: 128000
      token-estimator: char-ratio
      forward-headers:
        - Authorization
        - Content-Type

  compression:
    enabled: true
    max-array-elements: 20
    max-message-chars: 8000
    text-truncation-enabled: true
    truncation-head-sentences: 5
    truncation-tail-sentences: 3
    min-compress-chars: 200
    dedup-window-ms: 2000

  ccr:
    enabled: true
    db-path: ./data/ccr.db
    retention-days: 30
    min-original-chars: 200

spring:
  datasource:
    url: jdbc:sqlite:${contextcompresso.ccr.db-path}
    driver-class-name: org.sqlite.JDBC
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
  task:
    scheduling:
      pool:
        size: 1

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

---

## Project Structure

```
contextcompresso/
├── pom.xml
├── src/
│   ├── main/java/com/contextcompresso/
│   │   ├── ContextCompressoApplication.java
│   │   ├── config/
│   │   │   ├── AppConfig.java
│   │   │   ├── ProviderProperties.java
│   │   │   ├── CompressionProperties.java
│   │   │   └── SqliteConfig.java
│   │   ├── provider/
│   │   │   ├── Provider.java                 # enum: COPILOT, CLAUDE, DEFAULT
│   │   │   ├── ProviderRouter.java           # resolves ProviderConfig from request
│   │   │   ├── ProviderConfig.java           # resolved config for a provider
│   │   │   └── ProviderDetector.java         # auth-header / path heuristics
│   │   ├── controller/
│   │   │   ├── ProxyController.java
│   │   │   └── CcrController.java
│   │   ├── compression/
│   │   │   ├── CompressionPipeline.java
│   │   │   ├── SmartCrusher.java
│   │   │   ├── CodeCompressor.java
│   │   │   ├── TextTruncator.java
│   │   │   ├── CacheAligner.java
│   │   │   └── RequestDeduplicator.java
│   │   ├── tokenizer/
│   │   │   ├── TokenEstimator.java           # interface
│   │   │   ├── JtokkitEstimator.java         # for GPT/Copilot models
│   │   │   ├── AnthropicEstimator.java       # for Claude models
│   │   │   └── CharRatioEstimator.java       # fallback
│   │   ├── ccr/
│   │   │   ├── CcrStore.java
│   │   │   └── CcrEntry.java
│   │   ├── model/
│   │   │   ├── ChatRequest.java
│   │   │   ├── ChatMessage.java
│   │   │   ├── ContentBlock.java
│   │   │   ├── CompressedRequest.java
│   │   │   └── CompressionStats.java
│   │   └── util/
│   │       └── HashUtil.java
│   └── resources/
│       ├── application.yml
│       └── schema.sql
└── src/test/java/com/contextcompresso/
    ├── provider/
    │   └── ProviderDetectorTest.java
    ├── compression/
    │   ├── SmartCrusherTest.java
    │   ├── CodeCompressorTest.java
    │   ├── TextTruncatorTest.java
    │   ├── CacheAlignerTest.java
    │   └── RequestDeduplicatorTest.java
    ├── tokenizer/
    │   └── TokenEstimatorTest.java
    ├── ccr/
    │   └── CcrStoreTest.java
    └── integration/
        ├── CopilotProxyTest.java
        ├── ClaudeProxyTest.java
        └── EndToEndProxyTest.java
```

---

## Dependencies (pom.xml)

```xml
<project>
  <groupId>com.contextcompresso</groupId>
  <artifactId>contextcompresso</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
  </parent>

  <properties>
    <java.version>17</java.version>
  </properties>

  <dependencies>
    <!-- WebFlux for reactive proxy + SSE streaming -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>

    <!-- Config binding -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-configuration-processor</artifactId>
      <optional>true</optional>
    </dependency>

    <!-- SQLite -->
    <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.45.3.0</version>
    </dependency>

    <!-- Spring JDBC for SQLite access -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>

    <!-- Jackson -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>

    <!-- Tiktoken-compatible tokenizer for GPT/Copilot models -->
    <dependency>
      <groupId>com.knuddels</groupId>
      <artifactId>jtokkit</artifactId>
      <version>1.0.0</version>
    </dependency>

    <!-- Caffeine cache for request deduplication -->
    <dependency>
      <groupId>com.github.ben-manes.caffeine</groupId>
      <artifactId>caffeine</artifactId>
    </dependency>

    <!-- Actuator for health/metrics -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver</artifactId>
      <version>4.12.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

---

## Compression Pipeline Detail

### Pipeline Order

1. **CacheAligner** — normalize message order (provider-specific)
2. **SmartCrusher** — structural JSON cleanup
3. **CodeCompressor** — strip comments, collapse whitespace in code
4. **TextTruncator** — head+tail truncation for oversized content
5. **CcrStore** — store originals, inject reference IDs

### SmartCrusher

Operates on Jackson `JsonNode` trees:
- Prune null/empty fields
- Deduplicate repeated JSON keys (last-wins)
- Collapse runs of whitespace within string values
- Truncate arrays longer than `max-array-elements` (keep first ceil(N/2) + last floor(N/2), insert sentinel)

### CodeCompressor

Detects code in: (a) markdown fenced blocks, (b) ContentBlock with `type=code`.

- Strip `//` and `#` single-line comments (with string-literal awareness)
- Strip `/* */` block comments
- Collapse 3+ consecutive blank lines to 2
- Normalize 4-space indentation to tabs
- **Copilot aggressive mode:** also strip imports, collapse signatures

Language awareness: comment stripping only for `{java, javascript, typescript, python, go, rust, c, cpp, cs, kotlin, scala, swift}`. Unknown languages get blank-line collapse only.

### TextTruncator

- Estimate token count using provider-appropriate estimator
- If over threshold: keep first K sentences + last M sentences
- Insert `[...~N chars omitted...]` sentinel
- Uses `java.text.BreakIterator.getSentenceInstance(Locale.US)` for sentence splitting (handles abbreviations correctly)

### CacheAligner

Provider-specific behavior:
- **Claude:** Do NOT reorder. Ensure deterministic compression output for cache stability.
- **Copilot:** Do NOT reorder Copilot-injected system prompts. May reorder user-added system messages.
- **Default (OpenAI):** System messages first, sorted by content hash for KV-cache locality.

---

## CCR (Compressed Content Registry)

### Schema

```sql
CREATE TABLE IF NOT EXISTS ccr_entries (
    id          TEXT PRIMARY KEY,
    request_id  TEXT NOT NULL,
    message_idx INTEGER NOT NULL,
    content_key TEXT NOT NULL,
    original    TEXT NOT NULL,
    compressed  TEXT NOT NULL,
    char_delta  INTEGER NOT NULL,
    created_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ccr_request ON ccr_entries(request_id);
CREATE INDEX IF NOT EXISTS idx_ccr_created ON ccr_entries(created_at);
```

### CcrStore

```java
@Repository
public class CcrStore {
    public String store(CcrEntry entry);
    public List<String> storeAll(String requestId, List<CcrEntry> entries);
    public Optional<CcrEntry> findById(String id);
    public List<CcrEntry> findByRequestId(String requestId);
    public int purgeExpired(int retentionDays);
}
```

ID generation: `SHA-256(requestId + messageIdx + contentKey)` — deterministic and idempotent (`INSERT OR REPLACE`).

### CcrController

```java
@RestController
@RequestMapping("/ccr")
public class CcrController {
    @GetMapping("/{id}")
    public Mono<ResponseEntity<CcrEntry>> getById(@PathVariable String id);

    @GetMapping("/request/{requestId}")
    public Mono<ResponseEntity<List<CcrEntry>>> getByRequestId(@PathVariable String requestId);
}
```

---

## Proxy Controller

```java
@RestController
public class ProxyController {

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<Flux<String>>> chatCompletions(
        @RequestBody Mono<String> rawBody, ServerHttpRequest request
    ) {
        ProviderConfig config = providerRouter.resolve(request, "openai");
        return proxyWithCompression(rawBody, request, config);
    }

    @PostMapping("/v1/messages")
    public Mono<ResponseEntity<Flux<String>>> messages(
        @RequestBody Mono<String> rawBody, ServerHttpRequest request
    ) {
        ProviderConfig config = providerRouter.resolve(request, "anthropic");
        return proxyWithCompression(rawBody, request, config);
    }

    // Generic pass-through (no compression)
    @RequestMapping("/**")
    public Mono<ResponseEntity<Flux<DataBuffer>>> genericProxy(
        ServerHttpRequest request, @RequestBody(required = false) Mono<DataBuffer> body
    );
}
```

**Streaming:** Detect `"stream": true` in request body. If streaming, pipe SSE chunks back as `text/event-stream` without buffering.

**Response headers added:**
- `X-CC-Original-Chars` — pre-compression character count
- `X-CC-Compressed-Chars` — post-compression character count
- `X-CC-Ratio` — compression ratio (< 1.0 means savings)
- `X-CC-Request-Id` — UUID for CCR retrieval

---

## Operations and Observability

### Metrics (Micrometer / Actuator)

| Metric | Type | Tags |
|--------|------|------|
| `cc.requests.total` | counter | `provider`, `status` |
| `cc.compression.ratio` | distribution summary | `provider` |
| `cc.chars.saved` | counter | `provider` |
| `cc.tokens.saved` | counter | `provider` |
| `cc.upstream.duration` | timer | `provider`, `streaming` |
| `cc.compression.skipped` | counter | `reason` (error, below-threshold) |
| `cc.dedup.hits` | counter | `provider` |

### Scheduled Purge

```java
@Scheduled(cron = "0 0 2 * * *")  // 2am daily
public void purgeExpiredEntries() {
    int deleted = store.purgeExpired(props.retentionDays());
}
```

### Error Handling

| Scenario | Behavior |
|----------|----------|
| Upstream 4xx/5xx | Return upstream status + body as-is (never mask API errors) |
| Upstream timeout | Return 504 with JSON error body |
| Compression failure | **Fail-open:** log, skip compression, forward original |
| Deserialization failure | Return 400 with diagnostic message |
| Provider detection ambiguous | Fall through to DEFAULT config |

### Request ID Propagation

Generate `X-CC-Request-Id` UUID at controller entry. Thread through compression pipeline into CcrStore. Return in response headers.

---

## Test Strategy

Tests are written alongside each phase (not deferred).

### Unit Tests (JUnit 5, no Spring context)

**SmartCrusherTest:**
- Prunes null fields
- Deduplicates keys
- Truncates large arrays (50 → 20 + sentinel)
- Collapses whitespace

**CodeCompressorTest:**
- Strips Java block + inline comments
- Strips Python `#` comments
- Preserves string literals containing `//`
- Unknown language only collapses blank lines
- Copilot aggressive mode strips imports

**TextTruncatorTest:**
- Short text passes through unchanged
- Long text produces head + sentinel + tail
- Handles "Dr. Smith" without false sentence split
- Processes List<ContentBlock> correctly

**CacheAlignerTest:**
- Claude mode preserves original order
- Copilot mode preserves Copilot system prompts
- Default mode moves system messages first
- Prefix hash is stable (same input = same hash)

**ProviderDetectorTest:**
- `ghp_` token → COPILOT
- `Copilot-Integration-Id` header → COPILOT
- `sk-ant-` key → CLAUDE
- `/v1/messages` path → CLAUDE
- `X-CC-Provider` override wins over heuristics

**RequestDeduplicatorTest:**
- Same hash within window returns cached response
- Different hash executes normally
- Expired entries evicted after window

### Integration Tests (MockWebServer + full Spring context)

**EndToEndProxyTest:**
- Compresses and forwards; upstream receives smaller body
- `X-CC-Ratio < 1.0` in response headers
- CCR entries created in SQLite
- Streaming response forwarded correctly (SSE)
- Fail-open: broken compressor → request still reaches upstream

**CopilotProxyTest:**
- Copilot system prompts preserved verbatim
- Tool calls forwarded unchanged
- Auth header forwarded

**ClaudeProxyTest:**
- `cache_control` blocks preserved
- Message order unchanged
- `X-Api-Key` forwarded
- Large tool results truncated

---

## Implementation Sequence

```
Phase 1 (Scaffold + Provider Config) ─── 1.5 days
    └── Phase 2a (Models + TokenEstimator interface) ─── 0.5 days
            ├── Phase 2b (SmartCrusher + tests)        [parallel] ─── 1 day
            ├── Phase 2c (CodeCompressor + tests)      [parallel] ─── 1 day
            ├── Phase 2d (TextTruncator + tests)       [parallel] ─── 0.5 days
            ├── Phase 2e (CacheAligner + tests)        [parallel] ─── 0.5 days
            └── Phase 2f (TokenEstimators + tests)     [parallel] ─── 0.5 days
                    └── Phase 3 (Pipeline + Dedup) ─── 1 day
                            └── Phase 4 (Proxy + CCR + integration tests) ─── 2 days
                                    └── Phase 5 (Ops + metrics) ─── 1 day
```

---

## Complexity Summary

| Phase | Description | Complexity | Days |
|-------|-------------|------------|------|
| 1 | Scaffold + multi-provider config + SQLite | S | 1.5 |
| 2a | Models + TokenEstimator interface | S | 0.5 |
| 2b | SmartCrusher + tests | M | 1 |
| 2c | CodeCompressor + provider-aware + tests | M | 1 |
| 2d | TextTruncator + tests | S | 0.5 |
| 2e | CacheAligner + provider-specific + tests | M | 0.5 |
| 2f | JtokkitEstimator + AnthropicEstimator + tests | S | 0.5 |
| 3 | CompressionPipeline + RequestDeduplicator | M | 1 |
| 4 | ProxyController + CcrStore + integration tests | L | 2 |
| 5 | Ops, metrics, scheduled purge | S | 1 |
| **Total (sequential)** | | | **~8.5 days** |
| **Total (parallel 2b–2f)** | | | **~6 days** |

---

## Risk Mitigations

| Risk | Mitigation |
|------|-----------|
| Copilot endpoint URL changes | Config-driven, no hardcoded URLs |
| Claude API version changes | Forward `Anthropic-Version` header; test against beta |
| Compression corrupts code | String-literal-aware stripping; integration tests with real code |
| Token estimation drift | Periodic calibration tests comparing estimator vs actual API usage |
| SQLite contention under load | WAL mode + single-writer; migration path to PostgreSQL if >100 req/s |
| Provider detection false positive | `X-CC-Provider` header override always wins |
| Copilot system prompt format changes | Signature-based detection with fallback to "don't compress any system msg" mode |
| Cache hit regression for Claude | Determinism tests: same input through pipeline must produce byte-identical output |

---

## Future Considerations (Out of Scope for v1)

- **Semantic compression** — using a smaller LLM to summarize tool results instead of truncating
- **Per-model token budgets** — automatically compress to fit within a model's context window
- **Multi-user support** — tenant isolation if deployed as a shared service
- **PostgreSQL backend** — for higher-throughput deployments
- **Compression analytics dashboard** — visualize savings over time per provider
- **Plugin system** — allow custom compression stages via SPI
