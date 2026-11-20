import * as http from 'http';
import {
    ApiError, ApiResult, ChangesRequest, ChangesResponse, IndexStatus, MetricsSummary,
    RegisterRequest, RegisterResponse, SearchRequest, SearchResponse, WorkspaceInfo
} from './types';

const DEFAULT_TIMEOUT_MS = 5000;
const SEARCH_TIMEOUT_MS = 10000;

interface RawResponse {
    status: number;
    contentType: string;
    body: string;
}

function unreachable(message: string): ApiError {
    return { error: 'unreachable', message };
}

/**
 * Thin HTTP client over the proxy's /vatstash endpoints. Like StatsClient it is best-effort,
 * but it reports why a call failed instead of collapsing to null: the search tool and the
 * dashboard have to tell "proxy down" from "index disabled" from "bad request".
 * No method ever throws.
 */
export class VatStashClient {
    constructor(private readonly baseUrlProvider: () => string | null) {}

    getBaseUrl(): string | null {
        return this.baseUrlProvider();
    }

    register(request: RegisterRequest): Promise<ApiResult<RegisterResponse>> {
        return this.json<RegisterResponse>('POST', '/vatstash/workspaces', request);
    }

    listWorkspaces(): Promise<ApiResult<WorkspaceInfo[]>> {
        return this.json<WorkspaceInfo[]>('GET', '/vatstash/workspaces');
    }

    status(workspaceId: string): Promise<ApiResult<IndexStatus>> {
        return this.json<IndexStatus>('GET', `/vatstash/workspaces/${encodeURIComponent(workspaceId)}/status`);
    }

    pushChanges(workspaceId: string, changes: ChangesRequest): Promise<ApiResult<ChangesResponse>> {
        return this.json<ChangesResponse>('POST', `/vatstash/workspaces/${encodeURIComponent(workspaceId)}/changes`, changes);
    }

    reindex(workspaceId: string, full: boolean): Promise<ApiResult<IndexStatus>> {
        return this.json<IndexStatus>('POST', `/vatstash/workspaces/${encodeURIComponent(workspaceId)}/reindex`, { full });
    }

    remove(workspaceId: string): Promise<ApiResult<null>> {
        return this.json<null>('DELETE', `/vatstash/workspaces/${encodeURIComponent(workspaceId)}`);
    }

    search(request: SearchRequest): Promise<ApiResult<SearchResponse>> {
        return this.json<SearchResponse>('POST', '/vatstash/search', request, SEARCH_TIMEOUT_MS);
    }

    metricsSummary(days = 7): Promise<ApiResult<MetricsSummary>> {
        return this.json<MetricsSummary>('GET', `/vatstash/metrics/summary?days=${days}`);
    }

    /** Returns the raw CSV or Markdown body, or null on any failure. */
    async exportMetrics(format: 'csv' | 'md', days: number): Promise<string | null> {
        const raw = await this.request('GET', `/vatstash/metrics/export?days=${days}&format=${format}`, undefined, DEFAULT_TIMEOUT_MS);
        return 'status' in raw && raw.status === 200 ? raw.body : null;
    }

    private async json<T>(method: string, path: string, body?: unknown, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<ApiResult<T>> {
        const raw = await this.request(method, path, body, timeoutMs);
        if (!('status' in raw)) {
            return { ok: false, error: raw };
        }
        if (raw.status >= 200 && raw.status < 300) {
            if (raw.body.trim() === '') {
                return { ok: true, data: null as T }; // 204 and bodiless 202
            }
            try {
                return { ok: true, data: JSON.parse(raw.body) as T };
            } catch {
                return { ok: false, error: { error: 'invalid_response', message: 'The proxy returned a response that is not valid JSON.' } };
            }
        }
        return { ok: false, error: parseApiError(raw) };
    }

    private request(method: string, path: string, body: unknown, timeoutMs: number): Promise<RawResponse | ApiError> {
        const baseUrl = this.baseUrlProvider();
        if (!baseUrl) {
            return Promise.resolve(unreachable('The ContextCompresso proxy is not running.'));
        }
        return new Promise((resolve) => {
            let settled = false;
            const done = (value: RawResponse | ApiError): void => {
                if (!settled) {
                    settled = true;
                    resolve(value);
                }
            };
            try {
                const payload = body === undefined ? undefined : Buffer.from(JSON.stringify(body), 'utf8');
                const headers: Record<string, string> = { Accept: 'application/json, text/csv, text/markdown' };
                if (payload) {
                    headers['Content-Type'] = 'application/json';
                    headers['Content-Length'] = String(payload.length);
                }
                const req = http.request(`${baseUrl}${path}`, { method, headers, timeout: timeoutMs }, (res) => {
                    const chunks: Buffer[] = [];
                    res.on('data', (chunk: Buffer) => chunks.push(chunk));
                    res.on('end', () => done({
                        status: res.statusCode ?? 0,
                        contentType: String(res.headers['content-type'] ?? ''),
                        body: Buffer.concat(chunks).toString('utf8')
                    }));
                    res.on('error', (err) => done(unreachable(err.message)));
                });
                req.on('error', (err) => done(unreachable(err.message)));
                req.on('timeout', () => {
                    req.destroy();
                    done(unreachable('The proxy did not answer in time.'));
                });
                if (payload) {
                    req.write(payload);
                }
                req.end();
            } catch (err) {
                done(unreachable(err instanceof Error ? err.message : String(err)));
            }
        });
    }
}

function parseApiError(raw: RawResponse): ApiError {
    try {
        const parsed = JSON.parse(raw.body) as Partial<ApiError>;
        if (parsed && typeof parsed.error === 'string') {
            return { error: parsed.error, message: typeof parsed.message === 'string' ? parsed.message : parsed.error };
        }
    } catch {
        // fall through to the generic form
    }
    return { error: 'http_error', message: `The proxy answered HTTP ${raw.status}.` };
}
