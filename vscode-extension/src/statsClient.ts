import * as http from 'http';

export interface TokenTotals {
    input: number;
    output: number;
    cacheRead: number;
    cacheWrite: number;
}

export interface LiveStats {
    sessionKey: string | null;
    turns: number;
    tokens: TokenTotals;
    cacheHitRate: number;
    effectiveInputTokens: number;
    lastTurn: { input: number; output: number; cacheRead: number; deltaVsPrev: number } | null;
    compression: { originalChars: number; compressedChars: number; ratio: number };
}

export interface TopCostEntry {
    requestId: string;
    provider: string;
    model: string | null;
    effectiveInputTokens: number;
    inputTokens: number;
    outputTokens: number;
    cacheReadTokens: number;
    cacheWriteTokens: number;
    createdAt: number;
}

export interface TodayStats {
    sinceEpochMs: number;
    requestCount: number;
    totals: TokenTotals;
    effectiveInputTokens: number;
    cacheHitRate: number;
    byHour: { hour: number; effectiveInputTokens: number; requestCount: number }[];
    byModel: { model: string; requestCount: number; totals: TokenTotals; effectiveInputTokens: number }[];
}

const EMPTY_LIVE_STATS: LiveStats = {
    sessionKey: null,
    turns: 0,
    tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    cacheHitRate: 0,
    effectiveInputTokens: 0,
    lastTurn: null,
    compression: { originalChars: 0, compressedChars: 0, ratio: 1 }
};

/**
 * Thin HTTP client over the proxy's /stats endpoints. Every call is best-effort: the proxy
 * may be down, still starting, or briefly unreachable, and the dashboard must degrade to a
 * "not connected" state rather than throwing into VS Code's UI update path.
 */
export class StatsClient {
    constructor(private baseUrlProvider: () => string | null) {}

    async fetchLive(): Promise<LiveStats | null> {
        return this.getJson<LiveStats>('/stats/live') ?? null;
    }

    async fetchToday(): Promise<TodayStats | null> {
        return this.getJson<TodayStats>('/stats/today');
    }

    async fetchTopCosts(limit = 10): Promise<TopCostEntry[]> {
        const result = await this.getJson<TopCostEntry[]>(`/stats/top-costs?limit=${limit}`);
        return result ?? [];
    }

    async fetchVersion(): Promise<{ version: string; buildNumber: string } | null> {
        return this.getJson<{ version: string; buildNumber: string }>('/stats/version');
    }

    private getJson<T>(path: string): Promise<T | null> {
        const baseUrl = this.baseUrlProvider();
        if (!baseUrl) {
            return Promise.resolve(null);
        }
        return new Promise((resolve) => {
            const req = http.get(`${baseUrl}${path}`, { timeout: 2000 }, (res) => {
                if (res.statusCode !== 200) {
                    res.resume();
                    resolve(null);
                    return;
                }
                let body = '';
                res.on('data', (chunk) => { body += chunk; });
                res.on('end', () => {
                    try {
                        resolve(JSON.parse(body) as T);
                    } catch {
                        resolve(null);
                    }
                });
            });
            req.on('error', () => resolve(null));
            req.on('timeout', () => { req.destroy(); resolve(null); });
        });
    }
}

export { EMPTY_LIVE_STATS };
