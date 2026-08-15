package com.contextcompresso.stats;

public record TopCostEntry(
        String requestId,
        String provider,
        String model,
        long effectiveInputTokens,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long createdAt
) {
}
