package com.contextcompresso.stats;

import java.util.List;

public record TodayStatsResponse(
        long sinceEpochMs,
        int requestCount,
        TokenTotals totals,
        long effectiveInputTokens,
        double cacheHitRate,
        List<HourBucket> byHour,
        List<ModelBreakdown> byModel
) {
    public record HourBucket(int hour, long effectiveInputTokens, int requestCount) {
    }

    public record ModelBreakdown(String model, int requestCount, TokenTotals totals, long effectiveInputTokens) {
    }
}
