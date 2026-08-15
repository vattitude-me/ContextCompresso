package com.contextcompresso.usage;

public record UsageRecord(
        String requestId,
        String sessionKey,
        String provider,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        int originalChars,
        int compressedChars,
        Long durationMs,
        long createdAt
) {

    public long effectiveInputTokens(double cacheReadWeight, double cacheWriteWeight) {
        return inputTokens
                + Math.round(cacheReadTokens * cacheReadWeight)
                + Math.round(cacheWriteTokens * cacheWriteWeight);
    }
}
