package com.contextcompresso.vatstash.search;

import java.util.List;

public record SearchResponse(
        String searchId,
        String query,
        long tookMs,
        String freshness,
        List<SearchHit> results,
        SearchTotals totals,
        String text
) {
}
