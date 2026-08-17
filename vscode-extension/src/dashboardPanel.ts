import * as vscode from 'vscode';
import { StatsClient, LiveStats, TodayStats, TopCostEntry } from './statsClient';
import { ProxyManager } from './proxyManager';
import {
    isClaudeCodeConfigured, isCopilotConfigured, claudeCodeOverrideTarget,
    configureClaudeCode, configureCopilot, disconnectClaudeCode, disconnectCopilot,
    ConfigureResult
} from './clientConfig';
import { BUILD_NUMBER } from './buildInfo';

const POLL_MS_OPEN = 3000;

/**
 * The on-demand detail view. Kept deliberately simple: no chart library (this runs behind a
 * firewall — no CDN, and pulling in a dependency for four small visuals isn't worth it),
 * just inline SVG driven by VS Code's own theme tokens so it doesn't look broken in dark mode.
 */
export class DashboardPanel {
    private static current: DashboardPanel | undefined;
    private readonly panel: vscode.WebviewPanel;
    private pollHandle: ReturnType<typeof setInterval> | undefined;
    private disposables: vscode.Disposable[] = [];

    static show(context: vscode.ExtensionContext, statsClient: StatsClient, proxyManager: ProxyManager, extensionVersion: string): void {
        if (DashboardPanel.current) {
            DashboardPanel.current.panel.reveal();
            return;
        }
        DashboardPanel.current = new DashboardPanel(context, statsClient, proxyManager, extensionVersion);
    }

    private constructor(
        private readonly context: vscode.ExtensionContext,
        private readonly statsClient: StatsClient,
        private readonly proxyManager: ProxyManager,
        private readonly extensionVersion: string
    ) {
        this.panel = vscode.window.createWebviewPanel(
            'contextcompressoDashboard',
            'ContextCompresso Dashboard',
            vscode.ViewColumn.Beside,
            { enableScripts: true, retainContextWhenHidden: true }
        );
        this.panel.webview.html = this.renderShell();

        this.panel.onDidDispose(() => this.dispose(), null, this.disposables);
        this.panel.webview.onDidReceiveMessage(
            (message) => void this.handleMessage(message),
            null,
            this.disposables
        );

        // Lifecycle changes (start finishing, a crash, another window taking over) must reach
        // the panel the moment they happen — a 3s poll is fine for stats, but makes button
        // feedback feel broken.
        this.proxyManager.onStatusChange(() => void this.refresh(), null, this.disposables);

        void this.refresh();
        this.pollHandle = setInterval(() => void this.refresh(), POLL_MS_OPEN);
    }

    private async handleMessage(message: { type?: string }): Promise<void> {
        switch (message?.type) {
            case 'start':
                await this.proxyManager.start();
                this.reportLifecycle('start');
                return;
            case 'stop':
                await this.proxyManager.stop();
                this.reportLifecycle('stop');
                return;
            case 'restart':
                await this.proxyManager.restart();
                this.reportLifecycle('restart');
                return;
            case 'configureClaudeCode':
                await this.connectClient('claudeCode');
                return;
            case 'configureCopilot':
                await this.connectClient('copilot');
                return;
            case 'disconnectClaudeCode':
                await this.applyResult(await disconnectClaudeCode());
                return;
            case 'disconnectCopilot':
                await this.applyResult(await disconnectCopilot());
                return;
            case 'copyExport': {
                // The Claude Code *CLI* runs outside the extension host and never reads
                // VS Code settings, so "Connect" does nothing for it — this is its only path.
                const baseUrl = this.proxyManager.getBaseUrl();
                if (baseUrl) {
                    await vscode.env.clipboard.writeText(`export ANTHROPIC_BASE_URL=${baseUrl}`);
                    this.notify('Copied the export command. Paste it into the shell you run `claude` from.');
                }
                return;
            }
            case 'copyBaseUrl': {
                const baseUrl = this.proxyManager.getBaseUrl();
                if (baseUrl) {
                    await vscode.env.clipboard.writeText(baseUrl);
                    this.notify(`Copied ${baseUrl} to the clipboard.`);
                }
                return;
            }
            case 'openSettings':
                await vscode.commands.executeCommand('workbench.action.openSettings', 'contextcompresso');
                return;
            case 'openLogs':
                this.proxyManager.showLogs();
                return;
        }
    }

    /** Turns the post-action proxy state into a plain-language result the panel can show. */
    private reportLifecycle(action: 'start' | 'stop' | 'restart'): void {
        const status = this.proxyManager.getStatus();
        if (action === 'stop') {
            this.notify(status.state === 'stopped'
                ? 'Proxy stopped. Requests from your AI tools will now go straight to their providers.'
                : 'Could not stop the proxy cleanly — see the logs.', status.state !== 'stopped');
            return;
        }
        if (status.running) {
            this.notify(`Proxy ${action === 'restart' ? 'restarted' : 'started'} on ${this.proxyManager.getBaseUrl()}.`);
        } else {
            this.notify(status.lastError ?? 'The proxy did not come up. Open the logs for details.', true);
        }
    }

    /**
     * Connecting a client edits settings.json, which neither client re-reads live, so the
     * write alone is not the whole job — the panel has to say a reload is needed and offer it,
     * otherwise the user connects, sees no traffic, and concludes the tool is broken.
     */
    private async connectClient(which: 'claudeCode' | 'copilot'): Promise<void> {
        const baseUrl = this.proxyManager.getBaseUrl();
        if (!baseUrl) {
            this.notify('Start the proxy first — there is no address to point the client at yet.', true);
            return;
        }
        // Both of these settings are machine-wide, so this reroutes the client in every VS Code
        // window on this machine — including projects unrelated to this one. That's a
        // surprising blast radius to discover afterwards, so confirm it up front.
        const label = which === 'claudeCode' ? 'Claude Code' : 'GitHub Copilot';
        const confirm = await vscode.window.showWarningMessage(
            `Point ${label} at this proxy?`,
            {
                modal: true,
                detail: `This applies to every VS Code window on this machine, not just this project. `
                    + `While the proxy is stopped, ${label} requests will fail until you disconnect `
                    + `it again from this dashboard.`
            },
            'Connect'
        );
        if (confirm !== 'Connect') {
            return;
        }

        await this.applyResult(which === 'claudeCode'
            ? await configureClaudeCode(baseUrl)
            : await configureCopilot(baseUrl));
    }

    /** Repaints the checklist, reports the outcome in-panel, and offers a reload when needed. */
    private async applyResult(result: ConfigureResult): Promise<void> {
        await this.refresh(); // so the step ticks over immediately
        this.notify(result.message, !result.ok);

        if (result.ok && result.needsReload) {
            const choice = await vscode.window.showInformationMessage(result.message, 'Reload Window');
            if (choice === 'Reload Window') {
                await vscode.commands.executeCommand('workbench.action.reloadWindow');
            }
        }
    }

    private async refresh(): Promise<void> {
        const proxyStatus = this.proxyManager.getStatus();
        // Skip the four stat calls entirely when nothing is listening: they'd each burn a
        // 2s timeout and stall the panel's refresh loop while the proxy is down or booting.
        const [live, today, topCosts, proxyVersion] = proxyStatus.running
            ? await Promise.all([
                this.statsClient.fetchLive(),
                this.statsClient.fetchToday(),
                this.statsClient.fetchTopCosts(8),
                this.statsClient.fetchVersion()
            ])
            : [null, null, [] as TopCostEntry[], null];
        const baseUrl = this.proxyManager.getBaseUrl();
        // staleClaudeCode: an override exists but points elsewhere (e.g. a port from a previous
        // run). Indistinguishable from "not connected" without this, yet it actively breaks
        // Claude Code rather than merely bypassing the proxy.
        const claudeTarget = claudeCodeOverrideTarget();
        const clients = baseUrl
            ? {
                claudeCode: isClaudeCodeConfigured(baseUrl),
                copilot: isCopilotConfigured(baseUrl),
                staleClaudeCode: claudeTarget !== null && claudeTarget !== baseUrl ? claudeTarget : null
            }
            : { claudeCode: false, copilot: false, staleClaudeCode: null };
        const versions = {
            extension: this.extensionVersion,
            extensionBuild: BUILD_NUMBER,
            proxy: proxyVersion?.version ?? null,
            proxyBuild: proxyVersion?.buildNumber ?? null
        };
        this.panel.webview.postMessage({
            type: 'update',
            live, today, topCosts, proxyStatus, versions, clients, baseUrl
        });
    }

    /** Surfaces action feedback inside the panel itself — a corner toast is too easy to miss. */
    private notify(text: string, isError = false): void {
        this.panel.webview.postMessage({ type: 'notice', text, isError });
    }

    private dispose(): void {
        DashboardPanel.current = undefined;
        if (this.pollHandle) {
            clearInterval(this.pollHandle);
        }
        this.disposables.forEach((d) => d.dispose());
    }

    private renderShell(): string {
        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<style>
  :root {
    --cc-border: var(--vscode-widget-border, rgba(128,128,128,0.25));
    --cc-surface: var(--vscode-editorWidget-background, rgba(128,128,128,0.06));
    --cc-ok: var(--vscode-charts-green, #89d185);
    --cc-warn: var(--vscode-charts-orange, #e2a336);
    --cc-accent: var(--vscode-charts-blue, #3794ff);
  }
  * { box-sizing: border-box; }
  body {
    font-family: var(--vscode-font-family);
    font-size: 13px;
    color: var(--vscode-foreground);
    background: var(--vscode-editor-background);
    padding: 0 0 32px;
    margin: 0;
  }
  .wrap { max-width: 940px; margin: 0 auto; padding: 0 24px; }

  /* Header: status and lifecycle controls stay pinned so the proxy is never more than
     one click away, whatever the user has scrolled to. */
  header {
    position: sticky; top: 0; z-index: 20;
    background: var(--vscode-editor-background);
    border-bottom: 1px solid var(--cc-border);
    padding: 14px 0 12px;
  }
  .header-inner { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
  .brand { font-size: 15px; font-weight: 600; letter-spacing: -0.01em; }
  .status-chip {
    display: inline-flex; align-items: center; gap: 7px;
    border: 1px solid var(--cc-border); border-radius: 999px;
    padding: 4px 12px 4px 10px; font-size: 12px; font-weight: 500;
  }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--vscode-descriptionForeground); flex-shrink: 0; }
  .dot.ok { background: var(--cc-ok); }
  .dot.warn { background: var(--cc-warn); }
  .dot.err { background: var(--vscode-errorForeground, #f14c4c); }
  .dot.busy { background: var(--cc-accent); animation: pulse 1.1s ease-in-out infinite; }
  @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.25; } }
  .endpoint {
    font-family: var(--vscode-editor-font-family, monospace); font-size: 11px;
    color: var(--vscode-descriptionForeground); cursor: pointer;
    border: none; background: none; padding: 2px 4px; border-radius: 3px;
  }
  .endpoint:hover { background: var(--cc-surface); color: var(--vscode-foreground); }
  .header-actions { margin-left: auto; display: flex; gap: 6px; align-items: center; }

  button {
    background: var(--vscode-button-background); color: var(--vscode-button-foreground);
    border: 1px solid transparent; padding: 5px 12px; border-radius: 4px;
    cursor: pointer; font-size: 12px; font-family: inherit; white-space: nowrap;
  }
  button:hover:not(:disabled) { background: var(--vscode-button-hoverBackground); }
  button:disabled { opacity: 0.45; cursor: default; }
  button.secondary {
    background: var(--vscode-button-secondaryBackground, transparent);
    color: var(--vscode-button-secondaryForeground, var(--vscode-foreground));
    border-color: var(--cc-border);
  }
  button.secondary:hover:not(:disabled) { background: var(--vscode-button-secondaryHoverBackground, rgba(128,128,128,0.15)); }
  button.ghost { background: none; border-color: transparent; color: var(--vscode-textLink-foreground); padding: 5px 8px; }
  button.ghost:hover:not(:disabled) { background: var(--cc-surface); }

  h2 {
    font-size: 11px; text-transform: uppercase; letter-spacing: 0.07em; font-weight: 600;
    color: var(--vscode-descriptionForeground); margin: 28px 0 10px;
  }
  .card {
    background: var(--cc-surface); border: 1px solid var(--cc-border);
    border-radius: 8px; padding: 16px;
  }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; }
  .stat-value { font-size: 26px; font-weight: 600; line-height: 1.15; font-variant-numeric: tabular-nums; }
  .stat-value.ok { color: var(--cc-ok); }
  .stat-value.warn { color: var(--cc-warn); }
  .stat-label { font-size: 11px; color: var(--vscode-descriptionForeground); margin-top: 3px; }
  .stat-sub { font-size: 11px; color: var(--vscode-descriptionForeground); margin-top: 8px; }

  table { width: 100%; border-collapse: collapse; font-size: 12px; }
  td, th { text-align: left; padding: 6px 8px; }
  th { color: var(--vscode-descriptionForeground); font-weight: 500; font-size: 11px; }
  tbody tr:not(:last-child) td { border-bottom: 1px solid var(--cc-border); }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .muted { color: var(--vscode-descriptionForeground); }
  .bar-track { background: var(--vscode-input-background, rgba(128,128,128,0.2)); border-radius: 3px; height: 6px; overflow: hidden; }
  .bar-fill { height: 100%; background: var(--cc-accent); border-radius: 3px; }
  .bar-fill.ok { background: var(--cc-ok); }
  .bar-fill.warn { background: var(--cc-warn); }

  /* Setup is a checklist, not a banner: a vendor demo has to show what is done, what is
     left, and what each step will do — before the user commits to it. */
  .step { display: flex; gap: 12px; align-items: flex-start; padding: 12px 0; }
  .step:not(:last-child) { border-bottom: 1px solid var(--cc-border); }
  .step-mark {
    width: 20px; height: 20px; border-radius: 50%; flex-shrink: 0; margin-top: 1px;
    display: flex; align-items: center; justify-content: center;
    font-size: 11px; font-weight: 700;
    border: 1px solid var(--cc-border); color: var(--vscode-descriptionForeground);
  }
  .step-mark.done { background: var(--cc-ok); border-color: var(--cc-ok); color: var(--vscode-editor-background); }
  .step-mark.warn { background: var(--cc-warn); border-color: var(--cc-warn); color: var(--vscode-editor-background); }
  .step-body { flex: 1; min-width: 0; }
  .step-title { font-weight: 600; font-size: 12.5px; }
  .step-hint { font-size: 11.5px; color: var(--vscode-descriptionForeground); margin-top: 3px; line-height: 1.45; }
  .step-hint code {
    font-family: var(--vscode-editor-font-family, monospace); font-size: 11px;
    background: var(--vscode-textCodeBlock-background, rgba(128,128,128,0.15));
    padding: 1px 4px; border-radius: 3px;
  }
  .step-action { flex-shrink: 0; display: flex; gap: 6px; align-items: center; }

  .empty { text-align: center; padding: 28px 20px; }
  .empty .title { font-size: 14px; font-weight: 600; margin-bottom: 6px; }
  .empty .hint { color: var(--vscode-descriptionForeground); font-size: 12px; margin-bottom: 16px; line-height: 1.5; }
  .empty .actions { display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
  .error-detail {
    font-family: var(--vscode-editor-font-family, monospace); font-size: 11px;
    background: var(--vscode-textCodeBlock-background, rgba(128,128,128,0.1));
    border-radius: 4px; padding: 8px 10px; margin: 0 auto 16px; max-width: 560px;
    text-align: left; color: var(--vscode-foreground); white-space: pre-wrap; word-break: break-word;
  }
  .notice {
    position: fixed; left: 50%; transform: translateX(-50%); bottom: 24px; z-index: 40;
    max-width: 620px; border-radius: 6px; padding: 10px 14px; font-size: 12px;
    background: var(--vscode-notifications-background, var(--vscode-editorWidget-background));
    border: 1px solid var(--cc-border);
    box-shadow: 0 4px 16px rgba(0,0,0,0.35);
  }
  .notice.error { border-color: var(--vscode-errorForeground, #f14c4c); }
  footer { margin-top: 32px; font-size: 11px; color: var(--vscode-descriptionForeground);
           border-top: 1px solid var(--cc-border); padding-top: 12px; }
</style>
</head>
<body>
  <header><div class="wrap"><div class="header-inner" id="header"></div></div></header>
  <div class="wrap">
    <div id="root"><div class="empty"><div class="title">Loading…</div></div></div>
    <footer id="version-footer"></footer>
  </div>
  <div id="notice-container"></div>
  <script>
    const vscode = acquireVsCodeApi();
    const root = document.getElementById('root');
    const header = document.getElementById('header');

    const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g,
      (c) => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));

    function fmt(n) {
      if (n === null || n === undefined) return '—';
      if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
      if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
      return String(Math.round(n));
    }
    const pct = (n) => Math.round(n * 100) + '%';

    function bar(value, max, tone) {
      const width = max > 0 ? Math.min(100, (value / max) * 100) : 0;
      return '<div class="bar-track"><div class="bar-fill ' + (tone || '') + '" style="width:' + width + '%"></div></div>';
    }

    const STATE_UI = {
      starting: { dot: 'busy', label: 'Starting…' },
      running:  { dot: 'ok',   label: 'Running' },
      adopted:  { dot: 'ok',   label: 'Running (shared)' },
      stopping: { dot: 'busy', label: 'Stopping…' },
      stopped:  { dot: '',     label: 'Stopped' },
      error:    { dot: 'err',  label: 'Error' }
    };

    function renderHeader(data) {
      const st = data.proxyStatus;
      const ui = STATE_UI[st.state] || STATE_UI.stopped;
      const busy = st.busy;
      const isUp = st.state === 'running' || st.state === 'adopted';

      let html = '<span class="brand">ContextCompresso</span>';
      html += '<span class="status-chip"><span class="dot ' + ui.dot + '"></span>' + esc(ui.label) + '</span>';
      if (isUp && data.baseUrl) {
        html += '<button class="endpoint" id="copy-url" title="Copy proxy address">' + esc(data.baseUrl) + '</button>';
      }
      html += '<span class="header-actions">';
      if (isUp) {
        html += '<button class="secondary" id="btn-restart"' + (busy ? ' disabled' : '') + '>Restart</button>';
        // Adopted proxies belong to another window; "Stop" here only detaches this one, and
        // the label has to say so or it reads as a lie when the other window keeps working.
        html += '<button class="secondary" id="btn-stop"' + (busy ? ' disabled' : '') + '>' +
                (st.state === 'adopted' ? 'Disconnect' : 'Stop') + '</button>';
      } else {
        html += '<button id="btn-start"' + (busy ? ' disabled' : '') + '>Start proxy</button>';
      }
      html += '<button class="ghost" id="btn-logs">Logs</button>';
      html += '<button class="ghost" id="btn-settings">Settings</button>';
      html += '</span>';
      header.innerHTML = html;

      bind('copy-url', 'copyBaseUrl');
      bind('btn-restart', 'restart');
      bind('btn-stop', 'stop');
      bind('btn-start', 'start');
      bind('btn-logs', 'openLogs');
      bind('btn-settings', 'openSettings');
    }

    function bind(id, type) {
      const el = document.getElementById(id);
      if (el) el.addEventListener('click', () => vscode.postMessage({ type }));
    }

    function renderOffline(data) {
      const st = data.proxyStatus;
      if (st.state === 'starting' || st.state === 'stopping') {
        return '<div class="empty"><div class="title">' +
          (st.state === 'starting' ? 'Starting the proxy…' : 'Stopping the proxy…') + '</div>' +
          '<div class="hint">' + (st.state === 'starting'
            ? 'Waiting for the local proxy to accept connections. This usually takes a few seconds.'
            : 'Shutting down cleanly.') + '</div></div>';
      }
      if (st.state === 'error') {
        return '<div class="empty">' +
          '<div class="title">The proxy could not run</div>' +
          '<div class="error-detail">' + esc(st.lastError || 'Unknown error.') + '</div>' +
          '<div class="actions">' +
          '<button id="empty-start">Try again</button>' +
          '<button class="secondary" id="empty-logs">Open logs</button>' +
          '<button class="secondary" id="empty-settings">Open settings</button>' +
          '</div></div>';
      }
      return '<div class="empty">' +
        '<div class="title">Proxy is stopped</div>' +
        '<div class="hint">Your AI tools are talking to their providers directly.<br>' +
        'Start the proxy to compress requests and record token usage.</div>' +
        '<div class="actions"><button id="empty-start">Start proxy</button>' +
        '<button class="secondary" id="empty-logs">Open logs</button></div></div>';
    }

    // hint is trusted HTML assembled by the callers below (they escape any interpolated
    // values themselves); title is a plain literal.
    function step(state, title, hint, actionHtml) {
      const mark = state === 'done' ? '✓' : state === 'warn' ? '!' : '';
      return '<div class="step">' +
        '<div class="step-mark ' + state + '">' + mark + '</div>' +
        '<div class="step-body"><div class="step-title">' + title + '</div>' +
        '<div class="step-hint">' + hint + '</div></div>' +
        '<div class="step-action">' + (actionHtml || '') + '</div></div>';
    }

    /**
     * Shown until at least one client is routed through the proxy. Until that happens every
     * metric below reads zero, and without this the user cannot tell "nothing is wired up"
     * from "the product does not work".
     */
    function renderSetup(data) {
      const c = data.clients || {};
      const busy = data.proxyStatus.busy;
      const allConnected = c.claudeCode && c.copilot;
      let html = '<h2>' + (allConnected ? 'Connections' : 'Setup') + '</h2><div class="card">';
      html += step('done', 'Proxy running',
        'Listening on <code>' + esc(data.baseUrl || '—') + '</code> and ready to accept traffic.', '');

      const ccHint = c.staleClaudeCode
        ? 'Currently pointed at <code>' + esc(c.staleClaudeCode) + '</code>, which is not this proxy. ' +
          'Claude Code will fail until this is corrected or removed.'
        : 'Sets ANTHROPIC_BASE_URL for Claude Code in <strong>every</strong> VS Code window on this machine.';
      html += step(c.claudeCode ? 'done' : c.staleClaudeCode ? 'warn' : '', 'Connect Claude Code',
        c.claudeCode
          ? 'Pointed at the proxy. Using the <code>claude</code> CLI outside VS Code? Copy the export command.'
          : ccHint,
        c.claudeCode
          ? '<button class="secondary" id="copy-export">Copy CLI command</button>' +
            '<button class="ghost" id="unwire-claude"' + (busy ? ' disabled' : '') + '>Disconnect</button>'
          : '<button id="wire-claude"' + (busy ? ' disabled' : '') + '>' +
            (c.staleClaudeCode ? 'Repoint here' : 'Connect') + '</button>');

      html += step(c.copilot ? 'done' : '', 'Connect GitHub Copilot',
        c.copilot ? 'Pointed at the proxy. Reload the window if requests still bypass it.'
                  : 'Sets Copilot\\'s proxy override for every window on this machine. Reload afterwards to take effect.',
        c.copilot
          ? '<button class="ghost" id="unwire-copilot"' + (busy ? ' disabled' : '') + '>Disconnect</button>'
          : '<button class="secondary" id="wire-copilot"' + (busy ? ' disabled' : '') + '>Connect</button>');

      html += '</div>';
      return html;
    }

    function renderNow(data) {
      const live = data.live;
      const c = data.clients || {};
      const connected = c.claudeCode || c.copilot;
      let html = '<h2>This session</h2>';

      if (!live || live.turns === 0) {
        html += '<div class="card empty" style="padding:24px">' +
          '<div class="hint" style="margin:0">' +
          (connected
            ? 'Connected, but no requests captured yet. Send a prompt from your AI tool and this updates automatically.'
            : 'Finish the setup steps above to start capturing usage.') +
          '</div></div>';
        return html;
      }

      const hitTone = live.cacheHitRate >= 0.85 ? 'ok' : live.cacheHitRate >= 0.6 ? '' : 'warn';
      const saved = Math.max(0, 1 - (live.compression.ratio || 1));
      html += '<div class="grid">' +
        '<div class="card"><div class="stat-value ' + hitTone + '">' + pct(live.cacheHitRate) + '</div>' +
        '<div class="stat-label">Cache hit rate</div>' +
        '<div class="stat-sub">' + bar(live.cacheHitRate, 1, hitTone) + '</div></div>' +

        '<div class="card"><div class="stat-value">' + fmt(live.effectiveInputTokens) + '</div>' +
        '<div class="stat-label">Effective input tokens</div>' +
        '<div class="stat-sub">Billable input after cache discounts.</div></div>' +

        '<div class="card"><div class="stat-value">' + live.turns + '</div>' +
        '<div class="stat-label">Requests this session</div>' +
        '<div class="stat-sub">' + fmt(live.tokens.output) + ' output tokens returned.</div></div>' +

        '<div class="card"><div class="stat-value ' + (saved > 0 ? 'ok' : '') + '">' + pct(saved) + '</div>' +
        '<div class="stat-label">Request text removed</div>' +
        '<div class="stat-sub">Characters dropped before sending. An estimate — not the measured token count.</div></div>' +
        '</div>';
      return html;
    }

    function renderTopCosts(entries) {
      if (!entries || entries.length === 0) {
        return '<div class="muted">No requests recorded today.</div>';
      }
      const max = Math.max.apply(null, entries.map(e => e.effectiveInputTokens).concat([1]));
      const rows = entries.map((e) => {
        const time = new Date(e.createdAt).toLocaleTimeString();
        return '<tr>' +
          '<td>' + esc(e.model || e.provider) + '</td>' +
          '<td class="muted">' + esc(time) + '</td>' +
          '<td style="width:38%">' + bar(e.effectiveInputTokens, max) + '</td>' +
          '<td class="num">' + fmt(e.effectiveInputTokens) + '</td>' +
          '</tr>';
      }).join('');
      return '<table><thead><tr><th>Model</th><th>When</th><th></th><th class="num">Effective tokens</th></tr></thead><tbody>' +
        rows + '</tbody></table>';
    }

    function renderModelBreakdown(byModel) {
      if (!byModel || byModel.length === 0) return '<div class="muted">No data yet.</div>';
      const max = Math.max.apply(null, byModel.map(m => m.effectiveInputTokens).concat([1]));
      const rows = byModel.map(m =>
        '<tr><td>' + esc(m.model) + '</td><td class="muted">' + m.requestCount + ' requests</td>' +
        '<td style="width:40%">' + bar(m.effectiveInputTokens, max) + '</td>' +
        '<td class="num">' + fmt(m.effectiveInputTokens) + '</td></tr>'
      ).join('');
      return '<table><tbody>' + rows + '</tbody></table>';
    }

    function renderTrend(byHour) {
      if (!byHour || byHour.length === 0) return '<div class="muted">No activity yet today.</div>';
      const max = Math.max.apply(null, byHour.map(b => b.effectiveInputTokens).concat([1]));
      // Column chart rather than a polyline: hourly usage is discrete, and bars stay readable
      // with a single populated hour where a 1-point line renders as nothing at all.
      const bars = byHour.map(b => {
        const h = Math.max(2, (b.effectiveInputTokens / max) * 100);
        return '<div title="' + b.hour + ':00 — ' + fmt(b.effectiveInputTokens) + ' tokens, ' + b.requestCount + ' requests" ' +
          'style="flex:1;min-width:3px;height:' + h + '%;background:var(--cc-accent);border-radius:2px 2px 0 0;opacity:0.85"></div>';
      }).join('');
      return '<div style="display:flex;align-items:flex-end;gap:3px;height:72px">' + bars + '</div>' +
        '<div class="muted" style="font-size:11px;margin-top:8px">Effective input tokens per hour</div>';
    }

    function renderVersionFooter(v) {
      if (!v) return '';
      const parts = ['Extension v' + v.extension + (v.extensionBuild ? ' (build ' + v.extensionBuild + ')' : '')];
      if (v.proxy) parts.push('Proxy v' + v.proxy + (v.proxyBuild ? ' (build ' + v.proxyBuild + ')' : ''));
      return esc(parts.join('  ·  '));
    }

    function render(data) {
      document.getElementById('version-footer').textContent = renderVersionFooter(data.versions);
      renderHeader(data);

      const st = data.proxyStatus;
      if (!(st.state === 'running' || st.state === 'adopted')) {
        root.innerHTML = renderOffline(data);
        bind('empty-start', 'start');
        bind('empty-logs', 'openLogs');
        bind('empty-settings', 'openSettings');
        return;
      }

      // The setup card stays visible even when fully connected: it doubles as the
      // "what am I connected to" reference, and holds the CLI export action.
      let html = renderSetup(data);
      html += renderNow(data);
      html += '<h2>Largest requests today</h2><div class="card">' + renderTopCosts(data.topCosts) + '</div>';
      html += '<h2>Usage by hour</h2><div class="card">' + renderTrend(data.today ? data.today.byHour : []) + '</div>';
      html += '<h2>By model today</h2><div class="card">' + renderModelBreakdown(data.today ? data.today.byModel : []) + '</div>';

      root.innerHTML = html;
      bind('wire-claude', 'configureClaudeCode');
      bind('wire-copilot', 'configureCopilot');
      bind('unwire-claude', 'disconnectClaudeCode');
      bind('unwire-copilot', 'disconnectCopilot');
      bind('copy-export', 'copyExport');
    }

    let noticeTimer = null;
    function showNotice(text, isError) {
      const container = document.getElementById('notice-container');
      if (noticeTimer) clearTimeout(noticeTimer);
      container.innerHTML = '<div class="notice' + (isError ? ' error' : '') + '">' + esc(text) + '</div>';
      noticeTimer = setTimeout(() => { container.innerHTML = ''; }, 6000);
    }

    window.addEventListener('message', (event) => {
      if (event.data && event.data.type === 'update') {
        render(event.data);
      } else if (event.data && event.data.type === 'notice') {
        showNotice(event.data.text, event.data.isError);
      }
    });
  </script>
</body>
</html>`;
    }
}
