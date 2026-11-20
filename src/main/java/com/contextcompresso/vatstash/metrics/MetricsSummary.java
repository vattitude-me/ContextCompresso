package com.contextcompresso.vatstash.metrics;

import java.util.List;
import java.util.Map;

/** Response of {@code GET /vatstash/metrics/summary}. */
public record MetricsSummary(
        long sinceEpochMs,
        int days,
        int calls,
        Map<String, Integer> byClient,
        long returnedTokens,
        long sourceFileTokens,
        double estimatedReductionRatio,
        double avgChunksPerCall,
        long p50LatencyMs,
        long p95LatencyMs,
        int zeroResultCalls,
        List<Day> byDay,
        String note
) {
    public record Day(String day, int calls, long returnedTokens, long sourceFileTokens) {
    }
}
