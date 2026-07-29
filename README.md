# ContextCompresso

A locally running, reactive Java proxy that sits between AI coding tools (GitHub Copilot, Claude Code) and their LLM APIs. It intercepts outgoing chat/completion payloads, compresses them to cut token usage, stores the originals for later retrieval, and forwards the compressed request upstream — transparently, with no changes to your IDE or CLI beyond a base-URL override.

## What it does

- **Detects the calling provider** (Copilot, Claude, or a generic OpenAI-compatible default) from headers or the request path, and applies provider-specific compression rules.
- **Compresses JSON payloads**: prunes null/empty fields, deduplicates keys, collapses whitespace runs, and truncates oversized arrays.
- **Strips code comments and blank-line bloat** from fenced code blocks and code content, with awareness of common languages so string literals containing `//` or `#` are never mistaken for comments.
- **Truncates oversized text** (e.g. large tool outputs) using sentence-boundary-aware head+tail truncation, never mid-word or mid-sentence.
- **Preserves what must never change**: Claude's `cache_control` blocks and message order (so Anthropic's prompt-cache prefix matching isn't broken), and Copilot's injected system prompts and `tool_calls`/`functions` structures.
- **Stores original (uncompressed) content** in a local SQLite database (the "CCR" — Compressed Content Registry) so you can retrieve exactly what was sent before compression, per request or per message.
- **Streams responses** (including SSE) straight through without buffering the whole body in memory.
- **Fails open**: if compression ever throws, the original request is forwarded unmodified rather than blocking the call.

## How it works

| Component | Role |
|---|---|
| `ProviderRouter` / `ProviderDetector` | Resolves which provider config applies to a request (`X-CC-Provider` header override → auth-header heuristics → path → default) |
| `CompressionPipeline` | Orchestrates the stages below, provider-aware, fail-open |
| `CacheAligner` | Normalizes message order — never reorders for Claude/Copilot system prompts, sorts by content hash for the generic default |
| `SmartCrusher` | Structural JSON cleanup (null pruning, whitespace collapsing, array truncation) |
| `CodeCompressor` | Strips comments/blank lines from code blocks, string-literal aware |
| `TextTruncator` | Head+tail sentence truncation for oversized text, via `BreakIterator` |
| `CcrStore` | SQLite-backed store of original content, keyed by request/message, with scheduled purge |
| `ProxyController` | The actual HTTP proxy — `/v1/chat/completions`, `/v1/messages`, and a generic passthrough for everything else |

## Requirements

- Java 17+
- Maven 3.8+

## Getting started

```bash
# Build
mvn clean package

# Run (defaults to port 8080)
java -jar target/contextcompresso.jar
```

### Point Claude Code at it

```bash
export ANTHROPIC_BASE_URL=http://localhost:8080
```

### Point GitHub Copilot at it

```json
{
  "github.copilot.advanced": {
    "debug.overrideProxyUrl": "http://localhost:8080"
  }
}
```

No other client changes are needed — auth headers (`X-Api-Key`, `Authorization: Bearer ghp_...`) are forwarded upstream unchanged.

## Configuration

Configuration lives in `src/main/resources/application.yml` under `contextcompresso.*`, or can be overridden with `--contextcompresso.some.property=value` / environment variables. Key settings:

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | Port the proxy listens on |
| `contextcompresso.providers.copilot.base-url` | `https://api.githubcopilot.com` | Copilot upstream |
| `contextcompresso.providers.claude.base-url` | `https://api.anthropic.com` | Claude upstream |
| `contextcompresso.providers.default.base-url` | `https://api.openai.com` | Fallback upstream for any OpenAI-compatible endpoint |
| `contextcompresso.compression.enabled` | `true` | Master switch for the compression pipeline |
| `contextcompresso.compression.min-compress-chars` | `200` | Requests smaller than this pass through untouched |
| `contextcompresso.ccr.enabled` | `true` | Store originals for retrieval |
| `contextcompresso.ccr.db-path` | `./data/ccr.db` | SQLite database path |
| `contextcompresso.ccr.retention-days` | `30` | Originals older than this are purged nightly at 2am |

See `PLAN-v1.md` for the full architecture and configuration reference.

## Retrieving original (uncompressed) content

Every proxied response carries an `X-CC-Request-Id` header. Use it to fetch what was actually sent before compression:

```bash
curl http://localhost:8080/ccr/request/<request-id>
curl http://localhost:8080/ccr/<entry-id>
```

## Response headers

Every compressed response includes:

- `X-CC-Original-Chars` / `X-CC-Compressed-Chars` — character counts before/after compression
- `X-CC-Ratio` — compressed/original ratio (< 1.0 means savings)
- `X-CC-Request-Id` — UUID for CCR retrieval

## Metrics

Actuator is enabled at `/actuator/health`, `/actuator/info`, `/actuator/metrics`. Custom Micrometer metrics: `cc.requests.total`, `cc.compression.ratio`, `cc.chars.saved`, `cc.tokens.saved`, `cc.upstream.duration`, `cc.compression.skipped`, `cc.dedup.hits`.

## Testing

```bash
mvn test
```

47 tests covering provider detection, each compression stage in isolation, token estimators, CCR storage, and full end-to-end proxy behavior (via MockWebServer) for Copilot-, Claude-, and generic-style requests — including that upstream actually receives a smaller body, the client actually receives the full upstream response body back, CCR entries are created, and fail-open behavior holds when compression is skipped.

## License

Apache 2.0
