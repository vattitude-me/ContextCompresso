# ContextCompresso v2 — Usage Telemetry & VS Code Dashboard

## Problem statement

The team's constraint is a **credit bar metered on tokens**: input, output, cache-write, and
cache-read all draw down the same budget. Credits drain fastest when agentic commands run —
file reads, greps, and command output get dumped into context and resent on every subsequent
turn.

The team cannot currently plan around this because consumption is invisible until the bar is
already low.

**Goal:** make token spend visible per-request and per-session inside VS Code, so developers
can see what a command actually cost and adjust before the budget is gone.

---

## Phase 0 — Capture real usage (BLOCKING; everything depends on this)

### The gap

`ProxyController` streams the upstream response body directly to the client
(`ProxyController.java:151-153`) without inspecting it. The provider's `usage` block —
the authoritative record of what was billed — is never read.

Consequence: every existing metric (`cc.compression.ratio`, `cc.chars.saved`) measures
**characters removed from the request**. That is an estimate of savings. It is not cost.
A dashboard built on it would display confident numbers unrelated to the credit bar.

### What must be captured

Anthropic `/v1/messages` returns:

```json
"usage": {
  "input_tokens": 2095,
  "output_tokens": 503,
  "cache_creation_input_tokens": 18204,
  "cache_read_input_tokens": 154200
}
```

These four fields are the entire dashboard. They map directly onto the credit bar:

| Field | Meaning | Relative cost |
|---|---|---|
| `cache_read_input_tokens` | prefix served from cache | ~0.1x input |
| `input_tokens` | fresh, uncached input | 1x |
| `cache_creation_input_tokens` | written to cache this turn | ~1.25x input |
| `output_tokens` | generated | ~5x input |

OpenAI-style responses expose `usage.prompt_tokens` / `completion_tokens`, and
`prompt_tokens_details.cached_tokens` where available.

### Implementation: `UsageCapturingFilter`

The response is a `Flux<DataBuffer>` and must **stay streaming** — buffering the whole body
would break SSE and regress latency. Tap the stream instead of collecting it.

**Non-streaming responses:** wrap the flux with `.doOnNext()` accumulating into a bounded
buffer (cap ~64KB; `usage` always appears well within that). Parse on completion.

**Streaming (SSE) responses:** `usage` arrives in the terminal events. For Anthropic, the
`message_start` event carries input/cache counts and `message_delta` carries the final
`output_tokens`. Scan passing chunks for those event types rather than accumulating the
whole stream.

**Contract — identical to the compression pipeline's:** parsing is best-effort and fail-open.
A malformed or absent `usage` block records nothing and must never disturb the response
stream reaching the client.

### Schema

```sql
CREATE TABLE IF NOT EXISTS usage_records (
    request_id       TEXT PRIMARY KEY,
    session_key      TEXT,
    provider         TEXT NOT NULL,
    model            TEXT,
    workspace        TEXT,
    input_tokens         INTEGER NOT NULL DEFAULT 0,
    output_tokens        INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens    INTEGER NOT NULL DEFAULT 0,
    cache_write_tokens   INTEGER NOT NULL DEFAULT 0,
    original_chars   INTEGER NOT NULL DEFAULT 0,
    compressed_chars INTEGER NOT NULL DEFAULT 0,
    est_saved_tokens INTEGER NOT NULL DEFAULT 0,
    duration_ms      INTEGER,
    created_at       INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_created  ON usage_records(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_session  ON usage_records(session_key);
```

`session_key` — hash of the first N messages, stable across turns within a conversation
(reuse `HashUtil`). Groups a session's requests without the proxy holding session state.

### Exit criteria

Real `usage` numbers land in SQLite for both streaming and non-streaming requests, across
Copilot and Claude Code, with zero change to response latency or SSE behavior.

---

## Phase 1 — Aggregation API

Endpoints backing the dashboard. All read-only, all served off `boundedElastic` like the
existing `CcrController`.

```
GET /stats/live         → current session: token split, cache hit rate, last request cost
GET /stats/session/{k}  → one session's cumulative usage + per-turn series
GET /stats/today        → rolling 24h totals, grouped by model and workspace
GET /stats/top-costs    → most expensive individual requests, with what drove them
```

`/stats/live` response shape:

```json
{
  "sessionKey": "a3f9...",
  "turns": 14,
  "tokens": {
    "input": 24310, "output": 8420,
    "cacheRead": 512900, "cacheWrite": 31200
  },
  "cacheHitRate": 0.94,
  "effectiveInputTokens": 106_142,
  "lastTurn": { "input": 1840, "output": 620, "cacheRead": 48200, "deltaVsPrev": 0.03 },
  "compression": { "originalChars": 1284000, "compressedChars": 733000, "ratio": 0.43 }
}
```

**`effectiveInputTokens`** is the number that matters and the one to lead with:
cache-weighted input, i.e. what the credit bar actually sees.

```
effective = input + (cacheRead × 0.1) + (cacheWrite × 1.25)
```

Weights configurable per provider — they are pricing constants, not physics, and will drift.

---

## Phase 2 — The VS Code dashboard

Design principle: **glanceable by default, detail on demand.** A developer mid-task will not
open a panel. The status bar must carry the signal on its own.

### Status bar (always visible)

```
◐ 94% cached · 106k eff · ▲3%
```

- `94% cached` — cache hit rate. The single most actionable number: when it drops, cost spikes.
- `106k eff` — effective input tokens this session (cache-weighted).
- `▲3%` — trend vs. previous turn. Directional, catches runaway growth early.

Color thresholds: green ≥85% cached / amber 60–85% / red <60%.
A sudden red is almost always a broken cache prefix, and that is worth interrupting for.

### Hover tooltip

```
Session: 14 turns · 38 min
─────────────────────────────
Cache read     512,900   ░░░░░░░░░░  94%   (~51k billed)
Fresh input     24,310   ██              (~24k billed)
Cache write     31,200   █               (~39k billed)
Output           8,420   ▊               (~42k billed)
─────────────────────────────
Effective input  106,142 tokens
Compression      43% of request chars removed

Last turn: +1,840 input · 48,200 cached
Top cost this session:
  1. grep tool_result      18.2k tokens
  2. read src/Main.java    12.7k tokens
  3. bash test output       9.1k tokens
```

The **top-cost list is the highest-value element in the whole design.** It converts
"credits are draining" into "that recursive grep cost 18k tokens" — a specific,
changeable behavior. Everything else is context for this.

### Panel (webview, on click)

Four sections, in priority order:

1. **Now** — live token split (stacked bar), cache hit rate over the last 20 turns (sparkline)
2. **Cost drivers** — ranked tool results / file reads by token cost, with a "compress this
   aggressively" toggle per entry
3. **Trend** — today's consumption by hour, split by model
4. **Compression** — what the pipeline actually saved, honestly labelled as *estimated*
   token savings vs. *measured* usage. Never conflate the two.

### Implementation notes

- Poll `/stats/live` every ~3s while the panel is open; back off to 15s when it isn't.
  Status bar polls at 5s. No websockets — not worth the complexity for this.
- Webview must respect VS Code theme tokens (`--vscode-*` CSS vars). A dashboard that
  ignores dark mode reads as broken.
- Charts: hand-rolled inline SVG. No CDN (firewalled), and a charting library is a large
  dependency for four small visuals.
- Degrade gracefully when the proxy is down — grey status bar, "proxy not running", one-click
  restart. This will happen and should not look like a crash.

---

## Phase 3 — Act on what the dashboard reveals

Only build after Phase 2 has produced real data. The dashboard tells you which of these
is actually worth it — do not guess now.

1. **Aggressive tool-result compaction.** Tool results sit *after* the cached prefix, so
   rewriting them does not invalidate cache. Highest-value compression target given that
   "commands eat credits fast."
2. **Cache-prefix diagnostics.** Detect when a request's prefix diverges from the previous
   turn's and surface *why*. A broken prefix silently converts ~0.1x cache reads into 1x
   fresh input — likely a bigger drain than anything compression recovers.
3. **Budget alerts.** Warn at a configurable effective-token threshold per session.
4. **LLM summarization.** Last. Justify it against measured numbers from Phase 2, not
   intuition.

---

## Sequencing

| Phase | Scope | Why this order |
|---|---|---|
| 0 | Usage capture + schema | Nothing downstream is trustworthy without it |
| 1 | Stats API | Thin layer over Phase 0 |
| 2 | Extension: bundling, lifecycle, status bar, panel | First point of team value |
| 3 | Targeted optimization | Directed by Phase 2 data |

Phases 0–1 are backend-only and independently testable. Phase 2 is where the team sees
anything, so it should not slip — but shipping it on estimated rather than measured numbers
would undermine trust in the tool permanently.

---

## Known risks

- **Cache-weight constants drift.** Pricing changes. Keep weights in `application.yml`,
  never inline in code, and label the dashboard's derived figures as estimates.
- **SSE `usage` extraction is provider-specific and fragile.** Anthropic and OpenAI differ
  in both event names and field placement. Needs recorded-payload fixtures per provider,
  and must fail silently.
- **`session_key` is a heuristic.** Prefix-hashing breaks if a client mutates early messages
  between turns. Detect divergence and start a new session rather than silently merging two.
- **Storage growth.** `usage_records` is small per row, but `ccr_entries` stores full
  originals. The existing `CcrPurgeScheduler` covers CCR; extend retention to the new table.
- **Observer effect.** The dashboard must never itself become a cost. All aggregation is
  local SQLite; no upstream calls.
