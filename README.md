# ContextCompresso

A lightweight Java proxy that compresses LLM context — tool outputs, logs, and conversation history — before sending to any AI API, cutting token usage without changing your existing code.

## What it does

ContextCompresso sits between your application and any LLM API (Claude, OpenAI, or any OpenAI-compatible endpoint). It intercepts outgoing requests, compresses the context, forwards the compressed version, and returns the response — transparently.

Typical savings:
- **60–95%** token reduction on JSON payloads (tool outputs, structured logs)
- **15–20%** reduction on code and conversation history
- Zero changes required to your existing LLM client code

## How it works

| Component | Role |
|---|---|
| `ProxyController` | HTTP proxy — receives requests, returns responses |
| `SmartCrusher` | JSON compression — deduplicates keys, prunes nulls, truncates large arrays |
| `CodeCompressor` | Strips comments and redundant whitespace from code blocks |
| `TextTruncator` | Heuristic text shortening using sentence-boundary detection |
| `CacheAligner` | Normalizes message prefix order to maximize LLM KV-cache hits |
| `CcrStore` | SQLite-backed store — keeps originals for retrieval on demand |

## Requirements

- Java 17+
- Maven 3.8+

## Getting started

```bash
# Clone
git clone git@github.com:vattitude-me/ContextCompresso.git
cd ContextCompresso

# Build
mvn clean package

# Run (defaults to port 8787)
java -jar target/contextcompresso.jar
```

Point your LLM client at `http://localhost:8787` instead of the real API endpoint. No other changes needed.

## Configuration

| Property | Default | Description |
|---|---|---|
| `server.port` | `8787` | Port the proxy listens on |
| `compresso.target-url` | _(required)_ | The real LLM API base URL |
| `compresso.ccr.enabled` | `true` | Store originals for retrieval |
| `compresso.ccr.db-path` | `~/.compresso/ccr.db` | SQLite database path |

Set via `application.properties` or environment variables.

## License

Apache 2.0
