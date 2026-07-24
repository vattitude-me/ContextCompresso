# ContextCompresso — Full Implementation Plan

## Project Overview

ContextCompresso is a reactive Spring Boot proxy that intercepts LLM API requests, compresses message payloads to reduce token costs and latency, stores originals in SQLite for retrieval, and forwards compressed requests to any OpenAI-compatible or Anthropic endpoint.

---

## Maven Project Structure

```
contextcompresso/
├── pom.xml
├── README.md
├── .gitignore
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── contextcompresso/
│   │   │           ├── ContextCompressoApplication.java
│   │   │           ├── config/
│   │   │           │   ├── AppConfig.java
│   │   │           │   ├── CompressionProperties.java
│   │   │           │   ├── ProxyProperties.java
│   │   │           │   └── SqliteConfig.java
│   │   │           ├── controller/
│   │   │           │   ├── ProxyController.java
│   │   │           │   └── CcrController.java
│   │   │           ├── compression/
│   │   │           │   ├── CompressionPipeline.java
│   │   │           │   ├── SmartCrusher.java
│   │   │           │   ├── CodeCompressor.java
│   │   │           │   ├── TextTruncator.java
│   │   │           │   └── CacheAligner.java
│   │   │           ├── ccr/
│   │   │           │   ├── CcrStore.java
│   │   │           │   └── CcrEntry.java
│   │   │           ├── model/
│   │   │           │   ├── ChatRequest.java
│   │   │           │   ├── ChatMessage.java
│   │   │           │   ├── CompressedRequest.java
│   │   │           │   └── CompressionStats.java
│   │   │           └── util/
│   │   │               ├── HashUtil.java
│   │   │               └── TokenEstimator.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── schema.sql
│   └── test/
│       └── java/
│           └── com/
│               └── contextcompresso/
│                   ├── compression/
│                   │   ├── SmartCrusherTest.java
│                   │   ├── CodeCompressorTest.java
│                   │   ├── TextTruncatorTest.java
│                   │   └── CacheAlignerTest.java
│                   ├── ccr/
│                   │   └── CcrStoreTest.java
│                   ├── controller/
│                   │   └── ProxyControllerTest.java
│                   └── integration/
│                       └── EndToEndProxyTest.java
```

---

## pom.xml (Key Dependencies)

```xml
<project>
  <groupId>com.contextcompresso</groupId>
  <artifactId>contextcompresso</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

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

    <!-- Jackson (included transitively, but explicit for databind) -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
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

## Data Flow

```
Client App
    |
    | POST /v1/chat/completions  (or /v1/messages for Claude)
    v
ProxyController
    |
    |-- deserialize body --> ChatRequest (model, messages[], stream flag)
    |
    v
CompressionPipeline.compress(ChatRequest)
    |
    |-- [1] CacheAligner.align(messages)
    |        Sort/normalize system+user prefix for KV-cache locality
    |
    |-- [2] SmartCrusher.crush(messages)
    |        Per-message:
    |          - prune null/empty fields from JSON nodes
    |          - deduplicate repeated JSON keys in tool_result content
    |          - collapse repeated whitespace in string values
    |          - truncate large arrays (keep first N + last M elements)
    |
    |-- [3] CodeCompressor.compress(messages)
    |        Detect code blocks (``` fences or content_type=code)
    |          - strip single-line comments (// and #)
    |          - strip block comments (/* */ and /** */)
    |          - collapse blank lines > 2 consecutive
    |          - normalize indentation to tabs
    |
    |-- [4] TextTruncator.truncate(messages)
    |        For tool_result / long assistant messages:
    |          - estimate token count via char/4 heuristic
    |          - if over threshold: keep first K sentences + last M sentences
    |          - insert "[...N chars omitted...]" sentinel
    |
    |-- [5] CcrStore.storeOriginals(originalMessages, compressedMessages)
    |        Hash original content -> store in SQLite
    |        Inject CCR reference IDs into compressed messages as metadata
    |
    v
CompressedRequest
    |
    | re-serialize to JSON
    v
WebClient  -->  Target LLM API (OpenAI / Claude / any endpoint)
                  (passes through Authorization header)
    |
    v
Response (streaming SSE or JSON)
    |
    v
ProxyController  -->  Client App
```

---

## Phase Breakdown

### Phase 1 — Scaffold (Complexity: S, ~1 day)

Stand up the skeleton: Spring Boot app boots, health endpoint works, configuration loads, SQLite initializes.

**Tasks:**

1. Generate project via Spring Initializr or manually create pom.xml with deps listed above.

2. Create `ContextCompressoApplication.java`:
```java
@SpringBootApplication
public class ContextCompressoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContextCompressoApplication.class, args);
    }
}
```

3. Create `application.yml`:
```yaml
server:
  port: 8080

contextcompresso:
  proxy:
    target-url: https://api.openai.com
    connect-timeout-ms: 5000
    read-timeout-ms: 60000
    forward-headers:
      - Authorization
      - X-Api-Key
      - Content-Type
  compression:
    enabled: true
    max-array-elements: 20
    max-message-chars: 8000
    code-compression-enabled: true
    text-truncation-enabled: true
    cache-alignment-enabled: true
    truncation-head-sentences: 5
    truncation-tail-sentences: 3
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
```

4. Create `ProxyProperties.java` and `CompressionProperties.java` as `@ConfigurationProperties` records:
```java
@ConfigurationProperties(prefix = "contextcompresso.proxy")
public record ProxyProperties(
    String targetUrl,
    int connectTimeoutMs,
    int readTimeoutMs,
    List<String> forwardHeaders
) {}

@ConfigurationProperties(prefix = "contextcompresso.compression")
public record CompressionProperties(
    boolean enabled,
    int maxArrayElements,
    int maxMessageChars,
    boolean codeCompressionEnabled,
    boolean textTruncationEnabled,
    boolean cacheAlignmentEnabled,
    int truncationHeadSentences,
    int truncationTailSentences
) {}
```

5. Create `schema.sql` in resources:
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

6. Create `SqliteConfig.java` to configure the DataSource with WAL mode:
```java
@Configuration
public class SqliteConfig {
    @Bean
    public DataSource sqliteDataSource(CcrProperties props) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + props.dbPath());
        ds.setJournalMode("WAL");  // concurrent reads during proxy operation
        return ds;
    }
}
```

**Deliverables:** App starts, `/actuator/health` returns UP, SQLite file created with schema.

---

### Phase 2 — Core Compression Modules (Complexity: L, ~3 days)

Build the four compression workers in isolation — each independently testable with no Spring context. Phases 2b–2e are fully parallel; a four-person team can build all four simultaneously.

#### 2a. Model Classes

`ChatMessage.java`:
```java
public class ChatMessage {
    private String role;              // system, user, assistant, tool
    private Object content;           // String OR List<ContentBlock>
    private String toolCallId;
    private String name;
    private List<ToolCall> toolCalls;
}
```

`ContentBlock.java`:
```java
public class ContentBlock {
    private String type;    // text, image_url, tool_result, code
    private String text;
    private Object imageUrl;
}
```

`CompressionStats.java`:
```java
public record CompressionStats(
    int originalChars,
    int compressedChars,
    int messagesProcessed,
    Map<String, Integer> savingsByCompressor  // "code", "text", "json", "cache"
) {
    public double compressionRatio() {
        return originalChars == 0 ? 1.0 : (double) compressedChars / originalChars;
    }
}
```

#### 2b. SmartCrusher

Operates on `JsonNode` trees produced by Jackson's `ObjectMapper`.

```java
@Component
public class SmartCrusher {

    // Compress the entire messages array
    public List<ChatMessage> crush(List<ChatMessage> messages);

    // Recursively prune null/empty values from a JsonNode
    JsonNode pruneNulls(JsonNode node);

    // Collapse runs of whitespace within string leaves
    String collapseWhitespace(String value);

    // Deduplicate repeated keys in object nodes (last-wins)
    JsonNode deduplicateKeys(JsonNode node);

    // Truncate arrays longer than props.maxArrayElements()
    // Keeps first ceil(max/2) and last floor(max/2), inserts sentinel:
    // "[...N elements omitted...]"
    JsonNode truncateArrays(JsonNode node);

    // Serialize ChatMessage content through the above pipeline
    private Object compressContent(Object content);
}
```

#### 2c. CodeCompressor

Detects code in two ways: (a) markdown fenced blocks within text, (b) `ContentBlock` with `type=code`.

```java
@Component
public class CodeCompressor {

    // Process a single message's content
    public String compressText(String text);

    // Process a fenced code block string (content only, no fences)
    String compressCodeBlock(String code, String language);

    // Strip // and # single-line comments
    // Heuristic: count unescaped quotes before marker; if odd, skip (inside string)
    String stripSingleLineComments(String code);

    // Strip /* */ and /** */ block comments
    String stripBlockComments(String code);

    // Collapse runs of 3+ blank lines to 2 blank lines
    String collapseBlankLines(String code);

    // Convert 4-space indents to single tab
    String normalizeIndentation(String code);

    // Find all fenced code block regions in text
    List<CodeRegion> findCodeRegions(String text);

    record CodeRegion(int start, int end, String language, String content) {}
}
```

Language awareness: maintain `Set<String> COMMENT_LANGS` = `{java, javascript, typescript, python, go, rust, c, cpp, cs, kotlin, scala, swift}`. For unknown languages, only collapse blank lines (safe). Skip comment stripping for SQL, YAML, etc.

#### 2d. TextTruncator

```java
@Component
public class TextTruncator {

    // Truncate a single text string if it exceeds charThreshold
    // Returns original if under threshold
    public String truncate(String text, int charThreshold);

    // Split text into sentences using BreakIterator (locale-aware)
    List<String> splitSentences(String text);

    // Build truncated version: head sentences + sentinel + tail sentences
    // sentinel: "\n[...~N chars omitted...]\n"
    String buildTruncated(List<String> sentences, int headCount, int tailCount);

    // Estimate token count: chars / 4 (rough GPT tokenizer approximation)
    int estimateTokens(String text);

    // Process an entire message's content field
    // If content is List<ContentBlock>, truncate each text block independently
    public Object truncateContent(Object content, int charThreshold);
}
```

Use `java.text.BreakIterator.getSentenceInstance(Locale.US)` — correctly handles "Dr. Smith said..." without false splits.

#### 2e. CacheAligner

```java
@Component
public class CacheAligner {

    // Reorder messages to maximize KV-cache prefix stability:
    // Rule 1: system messages always first
    // Rule 2: within the system prefix, sort by content hash (deterministic)
    // Rule 3: do NOT reorder user/assistant turn pairs
    public List<ChatMessage> align(List<ChatMessage> messages);

    // Index of first non-system message
    int findPrefixBoundary(List<ChatMessage> messages);

    // Stable hash of system prefix for cache-control header injection
    public String computePrefixHash(List<ChatMessage> messages);

    boolean hasSamePrefix(List<ChatMessage> a, List<ChatMessage> b);
}
```

---

### Phase 3 — CompressionPipeline Orchestrator (Complexity: S, ~0.5 days)

```java
@Component
public class CompressionPipeline {

    // Compress a full ChatRequest, returns CompressedRequest with stats
    public CompressedRequest compress(ChatRequest request, String requestId);

    // Apply compression in pipeline order (each step checks its feature flag)
    private List<ChatMessage> applyPipeline(List<ChatMessage> messages, String requestId);

    private int totalChars(List<ChatMessage> messages);
}
```

`CompressedRequest.java`:
```java
public record CompressedRequest(
    ChatRequest originalRequest,
    ChatRequest compressedRequest,
    CompressionStats stats,
    String requestId,
    String cachePrefixHash
) {}
```

Pipeline order:
1. `CacheAligner.align()` — normalize order first
2. `SmartCrusher.crush()` — structural cleanup
3. `CodeCompressor` — per-message content
4. `TextTruncator` — after code stripped (shorter input)
5. `CcrStore.storeOriginals()` — store after all compression complete

---

### Phase 4 — ProxyController + WebClient (Complexity: M, ~1.5 days)

#### 4a. CcrStore

```java
@Repository
public class CcrStore {

    // Store a single original/compressed pair, return assigned ID
    public String store(CcrEntry entry);

    // Batch store for all changed messages in a request
    public List<String> storeAll(String requestId, List<CcrEntry> entries);

    // Retrieve original content by CCR ID
    public Optional<CcrEntry> findById(String id);

    // Retrieve all entries for a request ID
    public List<CcrEntry> findByRequestId(String requestId);

    // Delete entries older than retentionDays
    public int purgeExpired(int retentionDays);

    // SHA-256(requestId + messageIdx + contentKey) — deterministic, idempotent
    private String generateId(String requestId, int messageIdx, String contentKey);
}
```

`CcrEntry.java`:
```java
public record CcrEntry(
    String id,
    String requestId,
    int messageIdx,
    String contentKey,   // "content", "text", "tool_result", etc.
    String original,
    String compressed,
    int charDelta,
    Instant createdAt
) {}
```

#### 4b. ProxyController

```java
@RestController
public class ProxyController {

    // OpenAI-style chat completions (non-streaming + streaming)
    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<Flux<String>>> chatCompletions(
        @RequestBody Mono<String> rawBody,
        ServerHttpRequest request
    );

    // Anthropic messages API
    @PostMapping("/v1/messages")
    public Mono<ResponseEntity<Flux<String>>> anthropicMessages(
        @RequestBody Mono<String> rawBody,
        ServerHttpRequest request
    );

    // Generic catch-all pass-through (no compression)
    @RequestMapping("/**")
    public Mono<ResponseEntity<Flux<DataBuffer>>> genericProxy(
        ServerHttpRequest request,
        @RequestBody(required = false) Mono<DataBuffer> body
    );

    // Core: deserialize → compress → forward → stream back
    private Mono<ResponseEntity<Flux<String>>> proxyWithCompression(
        Mono<String> rawBody,
        ServerHttpRequest incomingRequest,
        String targetPath
    );

    // Response headers: X-CC-Original-Chars, X-CC-Compressed-Chars,
    //                   X-CC-Ratio, X-CC-Request-Id
    private HttpHeaders buildResponseHeaders(CompressedRequest compressed, HttpHeaders upstream);
}
```

WebClient configuration:
```java
@Bean
public WebClient webClient(ProxyProperties props) {
    HttpClient httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.connectTimeoutMs())
        .responseTimeout(Duration.ofMillis(props.readTimeoutMs()))
        .followRedirect(true);

    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
        .build();
}
```

Streaming: detect `"stream": true` in the request body. If streaming, pipe SSE chunks directly back as `text/event-stream` without buffering.

#### 4c. CcrController

```java
@RestController
@RequestMapping("/ccr")
public class CcrController {

    @GetMapping("/{id}")
    public Mono<ResponseEntity<CcrEntry>> getById(@PathVariable String id);

    @GetMapping("/request/{requestId}")
    public Mono<ResponseEntity<List<CcrEntry>>> getByRequestId(
        @PathVariable String requestId
    );
}
```

---

### Phase 5 — Operations and Hardening (Complexity: S, ~1 day)

**Scheduled purge:**
```java
@Scheduled(cron = "0 0 2 * * *")   // 2am daily
public void purgeExpiredEntries() {
    int deleted = store.purgeExpired(props.retentionDays());
}
```

**HashUtil:**
```java
public final class HashUtil {
    public static String sha256Hex(String input);
    public static String shortId(String... parts);  // first 12 hex chars
}
```

**Actuator metrics** (`MeterRegistry`):
- `cc.requests.total` (counter)
- `cc.compression.ratio` (distribution summary)
- `cc.chars.saved` (counter)
- `cc.upstream.duration` (timer)

**Error handling policy:**
- Upstream 4xx/5xx: return upstream status + body as-is (never mask API errors)
- Upstream timeout: return 504 with JSON error body
- Compression failure: **fail-open** — log, skip compression, forward original request
- Deserialization failure: return 400 with diagnostic message

A bug in `SmartCrusher` must never block a request. The fail-open policy is non-negotiable.

**Request ID propagation:** Generate `X-CC-Request-Id` UUID at controller entry. Propagate through `compress()` and into `CcrStore`. Return in response headers.

---

### Phase 6 — Tests (Complexity: M, ~2 days)

#### Unit Tests (plain JUnit 5, no Spring context)

`SmartCrusherTest`:
- `crushPrunesNullFields` — null `name` field absent from output
- `crushDeduplicatesKeys` — duplicate `role` keys → single key
- `crushTruncatesLargeArray` — 50 elements, max=20 → 20 + sentinel
- `crushCollapsesWhitespace` — triple spaces → single space

`CodeCompressorTest`:
- `compressStripsJavaComments` — block and inline comments removed
- `compressStripsHashComments` — Python `#` comments removed
- `compressPreservesStringsWithSlashes` — `"http://foo"` not corrupted
- `compressUnknownLanguageOnlyCollapsesBlankLines` — SQL not mangled
- `compressCollapsesBlanks` — 5 consecutive blank lines → 2

`TextTruncatorTest`:
- `truncateShortTextPassthrough` — text under threshold unchanged
- `truncateLongTextHasHeadAndTail` — 10k chars → head + sentinel + tail
- `truncateHandlesDrAbbreviation` — "Dr. Smith" not split at "."
- `truncateContentList` — List<ContentBlock> with long text → truncated

`CacheAlignerTest`:
- `alignMovesSystemFirst` — user before system → swapped
- `alignPreservesConversationOrder` — user/assistant pairs untouched
- `computePrefixHashIsStable` — same messages → same hash
- `computePrefixHashDiffersOnChange` — different system prompt → different hash

`CcrStoreTest` (in-memory SQLite: `jdbc:sqlite::memory:`):
- `storeAndRetrieve` — store, find by ID, fields match
- `findByRequestId` — 3 entries same requestId, retrieve all 3
- `purgeExpired` — old entry (epoch 0), purge 30 days → deleted

#### Integration Tests (`MockWebServer` + full Spring context)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndProxyTest {

    @Test void proxyCompressesAndForwards();
    // Assert: upstream received smaller body
    // Assert: X-CC-Compression-Ratio < 1.0 in response headers
    // Assert: CCR entries created in SQLite

    @Test void proxyStreamsResponseCorrectly();
    // Assert: response content-type is text/event-stream
    // Assert: each SSE chunk forwarded as received

    @Test void proxyFailsOpenOnCompressionError();
    // Inject broken SmartCrusher via @MockBean that throws
    // Assert: request still reaches upstream (original body)
    // Assert: response 200, not 500

    @Test void ccrRetrievalReturnsOriginal();
    // POST, extract X-CC-Request-Id
    // GET /ccr/request/{requestId}
    // Assert: original content matches what was sent
}
```

---

## Configuration Reference

```yaml
server:
  port: 8080

contextcompresso:
  proxy:
    target-url: https://api.openai.com
    connect-timeout-ms: 5000
    read-timeout-ms: 120000
    forward-headers:
      - Authorization
      - X-Api-Key
      - Content-Type
      - Accept
      - X-Request-Id

  compression:
    enabled: true
    max-array-elements: 20
    max-message-chars: 8000
    code-compression-enabled: true
    text-truncation-enabled: true
    cache-alignment-enabled: true
    truncation-head-sentences: 5
    truncation-tail-sentences: 3
    min-compress-chars: 200

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

## Implementation Sequence and Dependencies

```
Phase 1 (Scaffold)
    └── Phase 2a (Model classes)
            ├── Phase 2b (SmartCrusher)     [parallel]
            ├── Phase 2c (CodeCompressor)   [parallel]
            ├── Phase 2d (TextTruncator)    [parallel]
            └── Phase 2e (CacheAligner)     [parallel]
                    └── Phase 3 (CompressionPipeline)
                            └── Phase 4a (CcrStore)
                                    └── Phase 4b (ProxyController)
                                            └── Phase 4c (CcrController)
                                                    └── Phase 5 (Ops hardening)
                                                            └── Phase 6 (Tests)
```

Phases 2b–2e are fully parallel. A four-person team can build all four compressors simultaneously against the shared model from Phase 2a.

---

## Complexity Summary

| Phase | Description | Complexity | Est. Days |
|---|---|---|---|
| 1 | Scaffold, config, SQLite init | S | 1 |
| 2a | Model classes | S | 0.5 |
| 2b | SmartCrusher | M | 1 |
| 2c | CodeCompressor | M | 1 |
| 2d | TextTruncator | S | 0.5 |
| 2e | CacheAligner | S | 0.5 |
| 3 | CompressionPipeline | S | 0.5 |
| 4a | CcrStore | S | 0.5 |
| 4b | ProxyController + WebClient | M | 1.5 |
| 4c | CcrController | S | 0.5 |
| 5 | Ops, scheduling, metrics, error handling | S | 1 |
| 6 | Unit + integration tests | M | 2 |
| **Total (sequential)** | | | **~10 days** |
| **Total (parallel 2b–2e)** | | | **~7 days** |

---

## Key Design Decisions

**Why WebFlux?** Streaming LLM responses (SSE) require non-blocking I/O. WebFlux pipes SSE chunks directly from the upstream connection to the client without buffering, keeping memory flat regardless of response length.

**Why fail-open on compression errors?** A compression bug must never drop a user request. Wrapping the pipeline in a try-catch that falls back to the original request body costs nothing in the happy path and prevents silent outages.

**Why SQLite?** ContextCompresso is a local/sidecar proxy. SQLite with WAL mode handles concurrent reads and the single-writer workload without requiring a separate database process. The `ccr_entries` table only needs ID and request-ID lookups, both covered by the schema indexes.

**Why SHA-256 for CCR IDs?** Deterministic IDs mean re-compressing the same message produces the same CCR ID, enabling idempotent storage and deduplication at write time (`INSERT OR REPLACE`).

**Why BreakIterator for sentence splitting?** `String.split("\\.")` breaks on abbreviations, decimal numbers, and URLs. `BreakIterator` is locale-aware and handles common English abbreviations correctly without adding an NLP dependency.
