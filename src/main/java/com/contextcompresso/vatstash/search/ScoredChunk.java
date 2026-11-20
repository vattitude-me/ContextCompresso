package com.contextcompresso.vatstash.search;

/** A raw retrieval hit. {@code docId} is relative to the searcher that produced it. */
public record ScoredChunk(
        int docId,
        float score,
        String path,
        String language,
        String symbol,
        String kind,
        int startLine,
        int endLine,
        String content,
        int tokens
) {
}
