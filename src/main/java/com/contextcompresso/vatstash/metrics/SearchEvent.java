package com.contextcompresso.vatstash.metrics;

/** One row of {@code vatstash_search_events}. {@code workspaceIds} is comma separated. */
public record SearchEvent(
        String id,
        String workspaceIds,
        String client,
        int queryChars,
        String queryHash,
        String queryText,
        int results,
        int files,
        long returnedTokens,
        long sourceFileTokens,
        long tookMs,
        String freshness,
        long createdAt
) {
}
