# VatStash — Implementation Plan

**VAT = Vector Augmented Traversal** (the engine that traverses and vectorizes the codebase) **+ Stash** (the local cache that stores it for fast reuse)

## Product Vision

VatStash is a VS Code extension that maintains a locally-indexed, continuously-updated vector representation of a codebase, exposed to GitHub Copilot Chat as an invocable tool (`@vatstash`, or as an automatic agent-mode tool call). It reduces the context Copilot needs to send per query, lowers token cost, and improves answer accuracy for large monorepos and microservices — without ever blocking or slowing down developer workflows.

## Core Design Principles

1. **Non-blocking by default** — indexing runs in the background, asynchronously, never on the main thread or in the save/commit critical path.
2. **Optional and additive** — developers can use Copilot exactly as before; VatStash only adds capability when invoked or when Copilot's agent mode chooses to call it.
3. **Self-healing freshness** — the vector store updates incrementally on file changes, with no manual refresh commands required. Staleness is bounded and invisible to the user.
4. **Pure JVM footprint** — no Python or local LLM runtime required, since the team's machines are Java-only. Embeddings and search run natively in Java.
5. **Reasoning stays external** — actual answer generation is delegated to Copilot (or Claude); VatStash only supplies retrieval.

---

## Phase 1 — Core Indexing Engine (Day 1)

Build the foundational Java-based indexing pipeline.

- Choose a code-aware chunking strategy: parse files using an existing Java parser such as **JavaParser** or **Tree-sitter's Java grammar**, chunking by method, class, and Maven module boundaries rather than arbitrary line counts.
- Generate embeddings locally using **Deep Java Library (DJL)** or **ONNX Runtime for Java** with a small sentence-embedding model such as `all-MiniLM`, run entirely inside the JVM — no external services.
- Store vectors in an embedded vector-capable store: **SQLite with a vector extension**, or **Lucene's built-in vector search** (Lucene now supports approximate nearest neighbor search natively), so there's no external database dependency to install.
- Build the initial full-repo indexing job as a one-time bootstrap step, run once when the extension is first installed or opened on a workspace.

**Deliverable:** a command that indexes a sample repository and returns nearest-neighbor chunks for a test query.

---

## Phase 2 — Incremental Auto-Refresh (Day 1–2)

This is the piece that makes it feel invisible and safe.

- Hook into VS Code's file system watcher API to detect saves, renames, and deletes at the file level.
- On a file change, re-chunk and re-embed only the affected file or module, not the full repo, and upsert just those vectors into the store.
- Debounce rapid successive saves (e.g. wait 2–3 seconds of inactivity) so a burst of edits doesn't trigger repeated re-indexing.
- Run all of this on a background worker thread or VS Code's extension host background task API, so it never blocks typing, saving, or building.
- Add a lightweight staleness marker — an internal timestamp per file — so if a query hits a chunk that's mid re-index, VatStash can gracefully fall back to the previous version rather than erroring.

**Deliverable:** editing and saving a file automatically and silently updates only that file's vectors within a few seconds, with zero user action required.

---

## Phase 3 — Copilot Integration Layer (Day 2)

This is where VatStash becomes usable inside the existing developer workflow.

- Register VatStash as a **Language Model Tool** using VS Code's Language Model Tools API, so GitHub Copilot's agent mode can automatically discover and invoke it when relevant, without the user needing to type anything special.
- Additionally register a **Chat Participant**, `@vatstash`, for cases where a developer wants to explicitly query the index directly — e.g. `@vatstash explain how the payment service calls inventory`.
- The tool's function signature should accept a natural language query and optionally a scope hint (e.g. a module or service name), and return the top-matching code chunks plus file paths as structured context back to Copilot.
- Test both flows: implicit invocation during a normal Copilot Chat conversation, and explicit `@vatstash` invocation.

**Deliverable:** a working demo where asking Copilot a codebase question triggers a VatStash tool call under the hood, visibly reducing what needs to be manually pasted or opened.

---

## Phase 4 — Packaging and Team Distribution (Day 2–3)

- Package the extension as a **VSIX file** for manual distribution — no marketplace publishing needed for an internal pilot.
- Bundle the embedding model weights within the extension package, or fetch once on first run and cache locally, so there's no per-developer setup beyond installing the VSIX.
- Add a simple status bar indicator — e.g. a small icon showing index freshness (green = up to date, yellow = indexing in progress) — so it's transparent but never intrusive.
- Write a one-page internal README covering install steps, expected behavior, and how to report issues.

**Deliverable:** a shareable VSIX file that any team member can install with zero configuration.

---

## Phase 5 — Measurement and Leadership Reporting (Day 3)

This phase exists purely to build the business case.

- Instrument every VatStash tool call to log token counts — before/after comparisons: estimated tokens that would have been sent without VatStash (full file content) vs. tokens actually sent (retrieved chunks only).
- Track basic usage metrics: calls per day, average chunks returned, average file size vs. average chunk size sent.
- Build a simple aggregate report (even a markdown or CSV summary) showing: average context size reduced by X%, estimated cost savings based on current token pricing, and reduced latency per query.
- If time allows, run a short internal pilot with 2–3 volunteer developers for half a day before the presentation, to get one real before-and-after data point rather than only projected estimates.

**Deliverable:** a one-slide metrics summary showing measured or projected token and cost savings, ready to present.

---

## Suggested Timeline (3–4 Day Window)

| Day | Focus |
|---|---|
| **Day 1** | Phase 1 fully complete; start Phase 2 |
| **Day 2** | Finish Phase 2; complete Phase 3 end-to-end |
| **Day 3** | Phase 4 packaging, plus Phase 5 instrumentation and metrics (ideally with a small pilot test) |
| **Day 4** | Buffer day — bug fixes, polish, and building the leadership presentation deck around the Phase 5 metrics |

---

## Key Talking Points for Leadership

- Frame it as an **enhancement layer, not new infrastructure** — zero disruption to existing Copilot workflows, purely additive.
- Emphasize it runs entirely on **local Java tooling already present on every machine** — no new runtime dependencies, no security review overhead for external services since nothing leaves the laptop.
- Lead with the **cost and speed metrics from Phase 5** as the headline, then use the architecture as supporting detail.

---

*Next step: expand any single phase into more granular sub-tasks for your build agent — Phase 3 is the trickiest integration point and likely warrants the most detailed breakdown.*
