package com.contextcompresso.vatstash.search;

/** One returned snippet. Lines are 1-based and inclusive; {@code path} is relative to the root. */
public record SearchHit(
        String workspaceId,
        String path,
        String absolutePath,
        String language,
        String symbol,
        String kind,
        int startLine,
        int endLine,
        double score,
        String content,
        int tokens
) {
}
