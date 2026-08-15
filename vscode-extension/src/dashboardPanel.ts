import * as vscode from 'vscode';
import { StatsClient, LiveStats, TodayStats, TopCostEntry } from './statsClient';
import { ProxyManager } from './proxyManager';
import { isClaudeCodeConfigured, isCopilotConfigured, configureClaudeCode, configureCopilot } from './clientConfig';
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
        this.panel.webview.onDidReceiveMessage((message) => {
            if (message?.type === 'restart') {
                void this.proxyManager.restart();
            } else if (message?.type === 'configureClaudeCode') {
                const baseUrl = this.proxyManager.getBaseUrl();
                if (baseUrl) {
                    void configureClaudeCode(baseUrl).then(() => this.refresh());
                }
            } else if (message?.type === 'configureCopilot') {
                const baseUrl = this.proxyManager.getBaseUrl();
                if (baseUrl) {
                    void configureCopilot(baseUrl).then(() => this.refresh());
                }
            } else if (message?.type === 'openLogs') {
                this.proxyManager.showLogs();
            }
        }, null, this.disposables);

        void this.refresh();
        this.pollHandle = setInterval(() => void this.refresh(), POLL_MS_OPEN);
    }

    private async refresh(): Promise<void> {
        const [live, today, topCosts, proxyVersion] = await Promise.all([
            this.statsClient.fetchLive(),
            this.statsClient.fetchToday(),
            this.statsClient.fetchTopCosts(8),
            this.statsClient.fetchVersion()
        ]);
        const proxyStatus = this.proxyManager.getStatus();
        const baseUrl = this.proxyManager.getBaseUrl();
        const clients = baseUrl
            ? { claudeCode: isClaudeCodeConfigured(baseUrl), copilot: isCopilotConfigured(baseUrl) }
            : { claudeCode: false, copilot: false };
        const versions = {
            extension: this.extensionVersion,
            extensionBuild: BUILD_NUMBER,
            proxy: proxyVersion?.version ?? null,
            proxyBuild: proxyVersion?.buildNumber ?? null
        };
        this.panel.webview.postMessage({ type: 'update', live, today, topCosts, proxyStatus, versions, clients });
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
  body {
    font-family: var(--vscode-font-family);
    color: var(--vscode-foreground);
    background: var(--vscode-editor-background);
    padding: 16px 20px;
  }
  h2 { font-size: 13px; text-transform: uppercase; letter-spacing: 0.05em;
       color: var(--vscode-descriptionForeground); margin: 24px 0 10px; }
  h2:first-child { margin-top: 0; }
  .card {
    background: var(--vscode-sideBar-background, var(--vscode-editorWidget-background));
    border: 1px solid var(--vscode-widget-border, transparent);
    border-radius: 6px;
    padding: 14px 16px;
    margin-bottom: 8px;
  }
  .row { display: flex; gap: 12px; }
  .row > .card { flex: 1; }
  .stat-value { font-size: 22px; font-weight: 600; }
  .stat-label { font-size: 11px; color: var(--vscode-descriptionForeground); margin-top: 2px; }
  table { width: 100%; border-collapse: collapse; font-size: 12px; }
  td, th { text-align: left; padding: 4px 6px; }
  th { color: var(--vscode-descriptionForeground); font-weight: 500; }
  tr:not(:last-child) td { border-bottom: 1px solid var(--vscode-widget-border, rgba(128,128,128,0.15)); }
  .bar-track { background: var(--vscode-input-background); border-radius: 3px; height: 8px; overflow: hidden; }
  .bar-fill { height: 100%; background: var(--vscode-charts-blue, #3794ff); }
  .bar-fill.warn { background: var(--vscode-charts-orange, #e2a336); }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .muted { color: var(--vscode-descriptionForeground); }
  .disconnected { color: var(--vscode-errorForeground); }
  button {
    background: var(--vscode-button-background); color: var(--vscode-button-foreground);
    border: none; padding: 5px 12px; border-radius: 3px; cursor: pointer; font-size: 12px;
  }
  button:hover { background: var(--vscode-button-hoverBackground); }
  .sparkline { display: block; }
  .version-footer { margin-top: 24px; font-size: 11px; color: var(--vscode-descriptionForeground); }
  .banner {
    display: flex; align-items: center; justify-content: space-between; gap: 12px;
    background: var(--vscode-inputValidation-infoBackground, var(--vscode-editorWidget-background));
    border: 1px solid var(--vscode-inputValidation-infoBorder, var(--vscode-widget-border));
    border-radius: 6px; padding: 10px 14px; margin-bottom: 16px; font-size: 12px;
  }
  .banner .actions { display: flex; gap: 8px; flex-shrink: 0; }
  button.secondary {
    background: transparent; color: var(--vscode-button-foreground, var(--vscode-foreground));
    border: 1px solid var(--vscode-button-border, var(--vscode-widget-border));
  }
  button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground, rgba(128,128,128,0.15)); }
  .empty-state { text-align: center; padding: 32px 20px; }
  .empty-state .icon { font-size: 28px; margin-bottom: 8px; }
  .empty-state .title { font-size: 14px; font-weight: 600; margin-bottom: 6px; }
  .empty-state .hint { color: var(--vscode-descriptionForeground); font-size: 12px; margin-bottom: 16px; }
  .empty-state .actions { display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
</style>
</head>
<body>
  <div id="root">
    <div class="empty-state">
      <div class="icon">⏳</div>
      <div class="title">Connecting…</div>
    </div>
  </div>
  <div id="version-footer" class="version-footer"></div>
  <script>
    const vscode = acquireVsCodeApi();
    const root = document.getElementById('root');

    function fmt(n) {
      if (n === null || n === undefined) return '—';
      if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'm';
      if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k';
      return String(Math.round(n));
    }
    function pct(n) { return Math.round(n * 100) + '%'; }

    function renderBar(value, max, warn) {
      const width = max > 0 ? Math.min(100, (value / max) * 100) : 0;
      const cls = warn ? 'bar-fill warn' : 'bar-fill';
      return '<div class="bar-track"><div class="' + cls + '" style="width:' + width + '%"></div></div>';
    }

    function renderSparkline(hourBuckets) {
      const w = 320, h = 48, pad = 4;
      if (!hourBuckets || hourBuckets.length === 0) {
        return '<div class="muted">No activity yet today.</div>';
      }
      const max = Math.max(...hourBuckets.map(b => b.effectiveInputTokens), 1);
      const stepX = (w - pad * 2) / Math.max(hourBuckets.length - 1, 1);
      const points = hourBuckets.map((b, i) => {
        const x = pad + i * stepX;
        const y = h - pad - ((b.effectiveInputTokens / max) * (h - pad * 2));
        return x + ',' + y;
      }).join(' ');
      return '<svg class="sparkline" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '">' +
        '<polyline points="' + points + '" fill="none" stroke="var(--vscode-charts-blue, #3794ff)" stroke-width="2"/>' +
        '</svg>';
    }

    function renderTopCosts(entries) {
      if (!entries || entries.length === 0) {
        return '<div class="muted">No requests recorded today.</div>';
      }
      const max = Math.max(...entries.map(e => e.effectiveInputTokens));
      const rows = entries.map((e, i) => {
        const time = new Date(e.createdAt).toLocaleTimeString();
        return '<tr>' +
          '<td>' + (i + 1) + '</td>' +
          '<td>' + (e.model || e.provider) + '</td>' +
          '<td class="muted">' + time + '</td>' +
          '<td style="width:35%">' + renderBar(e.effectiveInputTokens, max, false) + '</td>' +
          '<td class="num">' + fmt(e.effectiveInputTokens) + '</td>' +
          '</tr>';
      }).join('');
      return '<table><thead><tr><th></th><th>Model</th><th>When</th><th></th><th class="num">Effective tokens</th></tr></thead><tbody>' + rows + '</tbody></table>';
    }

    function renderModelBreakdown(byModel) {
      if (!byModel || byModel.length === 0) return '<div class="muted">No data yet.</div>';
      const max = Math.max(...byModel.map(m => m.effectiveInputTokens));
      const rows = byModel.map(m =>
        '<tr><td>' + m.model + '</td><td class="muted">' + m.requestCount + ' reqs</td>' +
        '<td style="width:40%">' + renderBar(m.effectiveInputTokens, max, false) + '</td>' +
        '<td class="num">' + fmt(m.effectiveInputTokens) + '</td></tr>'
      ).join('');
      return '<table><tbody>' + rows + '</tbody></table>';
    }

    function renderVersionFooter(versions) {
      if (!versions) return '';
      const ext = 'Extension v' + versions.extension + (versions.extensionBuild ? ' (build ' + versions.extensionBuild + ')' : '');
      const parts = [ext];
      if (versions.proxy) {
        parts.push('Proxy v' + versions.proxy + (versions.proxyBuild ? ' (build ' + versions.proxyBuild + ')' : ''));
      }
      return parts.join(' · ');
    }

    function renderNotRunning(proxyStatus) {
      return '<div class="empty-state">' +
        '<div class="icon">⚠️</div>' +
        '<div class="title">Proxy not running</div>' +
        '<div class="hint">' + (proxyStatus.lastError || 'Start it to begin capturing usage data.') + '</div>' +
        '<div class="actions">' +
        '<button id="restart-btn">Start / Restart Proxy</button>' +
        '<button id="logs-btn" class="secondary">View Logs</button>' +
        '</div></div>';
    }

    function renderSetupBanner(clients) {
      if (clients.claudeCode || clients.copilot) return '';
      return '<div class="banner">' +
        '<span>Proxy is running, but no client is routed through it yet — usage will not appear until one is connected.</span>' +
        '<span class="actions">' +
        '<button id="wire-claude-btn">Connect Claude Code</button>' +
        '<button id="wire-copilot-btn" class="secondary">Connect Copilot</button>' +
        '</span></div>';
    }

    function renderConnectedBadge(clients) {
      const connected = [];
      if (clients.claudeCode) connected.push('Claude Code');
      if (clients.copilot) connected.push('Copilot');
      if (connected.length === 0) return '';
      return '<div class="muted" style="margin-bottom:12px;font-size:11px;">Connected: ' + connected.join(', ') + '</div>';
    }

    function render(data) {
      const { live, today, topCosts, proxyStatus, versions, clients } = data;
      document.getElementById('version-footer').textContent = renderVersionFooter(versions);

      if (!proxyStatus.running) {
        root.innerHTML = renderNotRunning(proxyStatus);
        document.getElementById('restart-btn')?.addEventListener('click', () => vscode.postMessage({ type: 'restart' }));
        document.getElementById('logs-btn')?.addEventListener('click', () => vscode.postMessage({ type: 'openLogs' }));
        return;
      }

      const hasLive = live && live.turns > 0;
      const hasAnyClient = clients && (clients.claudeCode || clients.copilot);

      let html = renderSetupBanner(clients || { claudeCode: false, copilot: false });
      html += renderConnectedBadge(clients || { claudeCode: false, copilot: false });

      html += '<h2>Now</h2>';
      if (hasLive) {
        html += '<div class="row">' +
          '<div class="card"><div class="stat-value">' + pct(live.cacheHitRate) + '</div><div class="stat-label">cache hit rate</div>' +
          renderBar(live.cacheHitRate, 1, live.cacheHitRate < 0.6) + '</div>' +
          '<div class="card"><div class="stat-value">' + fmt(live.effectiveInputTokens) + '</div><div class="stat-label">effective input tokens (this session)</div></div>' +
          '<div class="card"><div class="stat-value">' + live.turns + '</div><div class="stat-label">turns this session</div></div>' +
          '</div>';
      } else if (!hasAnyClient) {
        html += '<div class="card muted">Connect a client above to start seeing usage here.</div>';
      } else {
        html += '<div class="card muted">No activity recorded yet this session. Send a request through ' +
          (clients.claudeCode ? 'Claude Code' : 'Copilot') + ' and this will update automatically.</div>';
      }

      html += '<h2>Cost drivers (today)</h2><div class="card">' + renderTopCosts(topCosts) + '</div>';

      html += '<h2>Trend (today, by hour)</h2><div class="card">' + renderSparkline(today ? today.byHour : []) + '</div>';

      html += '<h2>By model (today)</h2><div class="card">' + renderModelBreakdown(today ? today.byModel : []) + '</div>';

      if (hasLive) {
        html += '<h2>Compression</h2><div class="card">' +
          '<span class="stat-value">' + pct(live.compression.ratio) + '</span> ' +
          '<span class="muted">of request characters kept (estimated savings — not the same as measured token usage above)</span>' +
          '</div>';
      }

      root.innerHTML = html;
      document.getElementById('wire-claude-btn')?.addEventListener('click', () => vscode.postMessage({ type: 'configureClaudeCode' }));
      document.getElementById('wire-copilot-btn')?.addEventListener('click', () => vscode.postMessage({ type: 'configureCopilot' }));
    }

    window.addEventListener('message', (event) => {
      if (event.data?.type === 'update') {
        render(event.data);
      }
    });
  </script>
</body>
</html>`;
    }
}
