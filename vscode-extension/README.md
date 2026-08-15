# ContextCompresso (VS Code extension)

Runs the [ContextCompresso](../README.md) proxy for you and surfaces live token/cache usage in VS Code, so you don't need a separate terminal running `java -jar contextcompresso.jar`.

## What it does

- Auto-detects a Java 17+ runtime (configured path → `JAVA_HOME` → `PATH`), picks a free port, and spawns the bundled proxy jar on startup.
- Status bar item: cache-hit percentage, effective token usage, and a trend arrow, updated every few seconds. Turns red when the cache hit rate drops below 60%.
- Dashboard webview (`ContextCompresso: Show Usage Dashboard`): cost drivers, hourly trend, per-model breakdown, compression savings.
- One-click config commands to point GitHub Copilot or Claude Code's integrated terminal at the local proxy.

## Building

From this directory:

```bash
./build.sh
```

This builds `contextcompresso.jar` from the parent Maven project, copies it into `resources/`, installs npm dependencies, compiles the TypeScript, and packages a `.vsix` if [`vsce`](https://www.npmjs.com/package/@vscode/vsce) is installed (`npm install -g @vscode/vsce`).

## Running in development

Open this directory in VS Code and press `F5` to launch an Extension Development Host with the extension loaded — no `.vsix` packaging needed for iterating locally.

## Configuration

| Setting | Default | Description |
|---|---|---|
| `contextcompresso.jarPath` | *(empty)* | Path to `contextcompresso.jar`. Empty uses the jar bundled with the extension. |
| `contextcompresso.port` | `8137` | Port the local proxy listens on. |
| `contextcompresso.autoStart` | `true` | Start the proxy automatically when VS Code opens. |
| `contextcompresso.javaPath` | *(empty)* | Path to a Java 17+ executable. Empty auto-detects. |

## Source layout

| File | Role |
|---|---|
| `src/proxyManager.ts` | Resolves Java/port, spawns and health-checks the proxy process |
| `src/statsClient.ts` | Polls the proxy's `/stats/*` endpoints, fails soft when unreachable |
| `src/statusBar.ts` | Renders the status bar item |
| `src/dashboardPanel.ts` | Renders the dashboard webview |
| `src/clientConfig.ts` | One-click Copilot/Claude Code configuration commands |
| `src/extension.ts` | Wires everything together and registers commands |
