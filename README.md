# ContextCompresso

A locally running, reactive Java proxy that sits between AI coding tools (GitHub Copilot, Claude Code) and their LLM APIs. It intercepts outgoing chat/completion payloads, compresses them to cut token usage, stores the originals for later retrieval, and forwards the compressed request upstream — transparently, with no changes to your IDE or CLI beyond a base-URL override.

## Requirements

- Java 17+
- Maven 3.8+

## Getting started

### Quick setup (recommended)

After cloning, run the setup script for your OS. It builds the jar, starts it, waits for a passing health check, and prints the exact config to point your chosen client at it:

```bash
# macOS / Linux
git clone <repo-url>
cd ContextCompresso
./scripts/setup.sh
```

```bat
:: Windows
git clone <repo-url>
cd ContextCompresso
scripts\setup.bat
```

You'll be prompted to choose **Claude Code** or **GitHub Copilot**, then the script prints the environment variable or VS Code `settings.json` snippet for that client. Skip ahead to [step 5](#5-send-a-sample-request-and-check-the-savings) to try it out.

### Manual setup

### 1. Clone and build

```bash
git clone <repo-url>
cd ContextCompresso
mvn clean package
```

### 2. Run it

```bash
# defaults to port 8137 (chosen to avoid clashing with common local dev
# ports like 8080/8081/3000); first run auto-creates ./data/ccr.db
java -jar target/contextcompresso.jar

# override the port if 8137 is also taken:
java -jar target/contextcompresso.jar --server.port=9137
```

### 3. Verify it's alive

In a second terminal:

```bash
curl http://localhost:8137/actuator/health
# {"status":"UP"}
```

### 4. Point a client at it

**Claude Code (VS Code extension or CLI)** reads `ANTHROPIC_BASE_URL` from its environment. Set it in the shell profile VS Code's integrated terminal uses (`~/.zshrc`, `~/.bashrc`, etc.) so it's picked up whenever the extension spawns the CLI, then restart the integrated terminal / VS Code:

```bash
export ANTHROPIC_BASE_URL=http://localhost:8137
```

Or, scoped to a single VS Code workspace, add it to `.vscode/settings.json` so only that workspace's integrated terminal (and the Claude Code extension running in it) picks it up:

```json
{
  "terminal.integrated.env.osx": {
    "ANTHROPIC_BASE_URL": "http://localhost:8137"
  }
}
```

(use `terminal.integrated.env.linux` / `.windows` on those platforms). Then open a fresh integrated terminal and run Claude Code as usual — no other config changes needed, since `X-Api-Key`/`Authorization` headers are forwarded upstream unchanged.

**GitHub Copilot**:

```json
{
  "github.copilot.advanced": {
    "debug.overrideProxyUrl": "http://localhost:8137"
  }
}
```

### 5. Send a sample request and check the savings

Trigger any Claude Code prompt in VS Code (or `curl` `/v1/messages` directly), then check the response headers on the traffic ContextCompresso just proxied:

```bash
curl -sD - http://localhost:8137/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{"model":"claude-sonnet-5","max_tokens":100,"messages":[{"role":"user","content":"say hi"}]}' \
  -o /dev/null
```

Look for these response headers on any real (larger) request:

```
X-CC-Original-Chars: 4213
X-CC-Compressed-Chars: 2870
X-CC-Ratio: 0.68
X-CC-Request-Id: 3f9e2c1a-...
```

For cumulative savings across every request the proxy has handled so far, query the built-in metrics:

```bash
curl -s http://localhost:8137/actuator/metrics/cc.tokens.saved | jq
curl -s http://localhost:8137/actuator/metrics/cc.chars.saved | jq
curl -s http://localhost:8137/actuator/metrics/cc.compression.ratio | jq
```

To pull back the exact original (uncompressed) payload for any request, use the `X-CC-Request-Id` from its response headers:

```bash
curl http://localhost:8137/ccr/request/<request-id>
```

## Configuration

Configuration lives in `src/main/resources/application.yml` under `contextcompresso.*`, or can be overridden with `--contextcompresso.some.property=value` / environment variables. Key settings:

| Property | Default | Description |
|---|---|---|
| `server.port` | `8137` | Port the proxy listens on |
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
curl http://localhost:8137/ccr/request/<request-id>
curl http://localhost:8137/ccr/<entry-id>
```

## Response headers

Every compressed response includes:

- `X-CC-Original-Chars` / `X-CC-Compressed-Chars` — character counts before/after compression
- `X-CC-Ratio` — compressed/original ratio (< 1.0 means savings)
- `X-CC-Request-Id` — UUID for CCR retrieval

## Metrics

Actuator is enabled at `/actuator/health`, `/actuator/info`, `/actuator/metrics`. Custom Micrometer metrics: `cc.requests.total`, `cc.compression.ratio`, `cc.chars.saved`, `cc.tokens.saved`, `cc.upstream.duration`, `cc.compression.skipped`, `cc.dedup.hits`.

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

## Testing

```bash
mvn test
```

47 tests covering provider detection, each compression stage in isolation, token estimators, CCR storage, and full end-to-end proxy behavior (via MockWebServer) for Copilot-, Claude-, and generic-style requests — including that upstream actually receives a smaller body, the client actually receives the full upstream response body back, CCR entries are created, and fail-open behavior holds when compression is skipped.

## License

Apache 2.0
