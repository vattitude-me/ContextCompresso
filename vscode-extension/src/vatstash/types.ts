/**
 * Mirrors the VatStash HTTP contract served by the proxy (see the plan, section 3.0).
 * Field names match the JSON exactly so responses can be cast without mapping.
 */

export type IndexState = 'building' | 'updating' | 'ready' | 'error';

export interface IndexStatus {
    workspaceId: string;
    root: string;
    state: IndexState;
    freshness: 'fresh' | 'stale';
    filesIndexed: number;
    filesSkipped: number;
    chunksIndexed: number;
    pendingFiles: number;
    progress: { done: number; total: number };
    indexBytes: number;
    lastFullScanAt: number | null;
    lastUpdateAt: number | null;
    truncated: boolean;
    lastError: string | null;
}

export interface WorkspaceSettings {
    excludeGlobs?: string[];
    maxFileBytes?: number;
    maxFiles?: number;
}

export interface RegisterRequest {
    root: string;
    settings?: WorkspaceSettings;
    client?: string;
}

export interface RegisterResponse {
    workspaceId: string;
    root: string;
    status: IndexStatus;
}

export interface WorkspaceInfo {
    workspaceId: string;
    root: string;
    status: IndexStatus;
}

export interface ChangesRequest {
    changed: string[];
    deleted: string[];
}

export interface ChangesResponse {
    accepted: number;
    ignored: number;
}

export type SearchClient = 'copilot-tool' | 'chat-participant' | 'mcp' | 'command' | 'http';

export interface SearchRequest {
    query: string;
    workspaceIds?: string[];
    root?: string | null;
    maxResults?: number;
    maxTokens?: number;
    pathPrefix?: string;
    languages?: string[];
    client: SearchClient;
}

export interface SearchHit {
    workspaceId: string;
    /** Relative to the workspace root, `/` separators. */
    path: string;
    absolutePath: string;
    language: string;
    symbol: string | null;
    kind: string;
    /** 1-based, inclusive. */
    startLine: number;
    endLine: number;
    score: number;
    content: string;
    tokens: number;
}

export interface SearchTotals {
    returnedTokens: number;
    sourceFileTokens: number;
    filesMatched: number;
    chunksReturned: number;
    omittedByBudget: number;
}

export interface SearchResponse {
    searchId: string;
    query: string;
    tookMs: number;
    freshness: 'fresh' | 'stale';
    results: SearchHit[];
    totals: SearchTotals;
    /** Ready-to-send text, always starting with the `[vatstash v1]` marker. */
    text: string;
}

export interface MetricsDay {
    day: string;
    calls: number;
    returnedTokens: number;
    sourceFileTokens: number;
}

export interface MetricsSummary {
    sinceEpochMs: number;
    days: number;
    calls: number;
    byClient: Record<string, number>;
    returnedTokens: number;
    sourceFileTokens: number;
    estimatedReductionRatio: number;
    avgChunksPerCall: number;
    p50LatencyMs: number;
    p95LatencyMs: number;
    zeroResultCalls: number;
    byDay: MetricsDay[];
    note: string;
}

export interface ApiError {
    /** Contract codes (invalid_request, invalid_root, forbidden, workspace_not_found, not_found,
     *  vatstash_disabled, vatstash_unavailable), plus the client-side `unreachable`. */
    error: string;
    message: string;
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: ApiError };
