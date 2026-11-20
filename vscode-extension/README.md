# ContextCompresso (VS Code extension)

Runs the [ContextCompresso](../README.md) proxy for you and surfaces live token/cache usage in VS Code, so you don't need a separate terminal running `java -jar contextcompresso.jar`.

## What it does

- Auto-detects a Java 17+ runtime (configured path → `JAVA_HOME` → `PATH`), picks a free port, and spawns the bundled proxy jar on startup.
- Status bar item: cache-hit percentage, effective token usage, and a trend arrow, updated every few seconds. Turns red when the cache hit rate drops below 60%.
- Dashboard webview (`ContextCompresso: Show Usage Dashboard`): cost drivers, hourly trend, per-model breakdown, compression savings.
- One-click config commands to point GitHub Copilot or Claude Code's integrated terminal at the local proxy.
- VatStash local code search: registers your folders with the proxy, forwards file change paths, and exposes search to Copilot (`#vatstash` tool, `@vatstash` chat participant) and Claude Code (via a copied `claude mcp add` command). The tool needs VS Code 1.95+ and the participant 1.90+, and the extension detects both at runtime. See the [main README](../README.md#code-search-vatstash).

## Building

From this directory:

```bash
./build.sh
```

This builds `contextcompresso.jar` from the parent Maven project, copies it into `resources/`, installs npm dependencies, compiles the TypeScript, and packages a `.vsix` if [`vsce`](https://www.npmjs.com/package/@vscode/vsce) is installed (`npm install -g @vscode/vsce`).

## Running in development

Open this directory in VS Code and press `F5` to launch an Extension Development Host with the extension loaded - no `.vsix` packaging needed for iterating locally.

## Configuration

| Setting | Default | Description |
|---|---|---|
| `contextcompresso.jarPath` | *(empty)* | Path to `contextcompresso.jar`. Empty uses the jar bundled with the extension. |
| `contextcompresso.port` | `8137` | Port the local proxy listens on. |
| `contextcompresso.autoStart` | `true` | Start the proxy automatically when VS Code opens. |
| `contextcompresso.javaPath` | *(empty)* | Path to a Java 17+ executable. Empty auto-detects. |
| `contextcompresso.vatstash.enabled` | `true` | Index open folders and offer code search to Copilot and Claude Code. |
| `contextcompresso.vatstash.excludeGlobs` | `[]` | Extra glob patterns to leave out of the index. |
| `contextcompresso.vatstash.maxFileSizeKb` | `512` | Files larger than this are not indexed. |
| `contextcompresso.vatstash.maxFiles` | `50000` | Most files indexed per folder. |
| `contextcompresso.vatstash.maxResults` | `8` | Snippets returned per search. |
| `contextcompresso.vatstash.maxResultTokens` | `4000` | Token budget for one search result. |
| `contextcompresso.vatstash.showStatusBar` | `true` | Show the VatStash index status bar item. |

## Source layout

| File | Role |
|---|---|
| `src/proxyManager.ts` | Resolves Java/port, spawns and health-checks the proxy process |
| `src/statsClient.ts` | Polls the proxy's `/stats/*` endpoints, fails soft when unreachable |
| `src/statusBar.ts` | Renders the status bar item |
| `src/dashboardPanel.ts` | Renders the dashboard webview |
| `src/clientConfig.ts` | One-click Copilot/Claude Code configuration commands |
| `src/extension.ts` | Wires everything together and registers commands |
| `src/vatstash/types.ts` | Request and response types for the `/vatstash` API |
| `src/vatstash/vatstashClient.ts` | HTTP client for the `/vatstash` endpoints |
| `src/vatstash/changeBatcher.ts` | Debounces file change events (1.5 s) and batches paths, dropping obvious build and dependency folders |
| `src/vatstash/workspaceSync.ts` | Registers folders with the proxy, watches files, pushes changed paths, polls index status |
| `src/vatstash/searchTool.ts` | The `vatstash_search` language model tool for Copilot (registered when the host supports it) |
| `src/vatstash/chatParticipant.ts` | The `@vatstash` chat participant and its `/search` command (registered when the host supports it) |
| `src/vatstash/vatstashStatusBar.ts` | The index status bar item |
| `src/vatstash/commands.ts` | The `contextcompresso.vatstash.*` commands |
| `src/test/changeBatcher.test.ts` | Unit tests for the change batcher (`node:test`) |
