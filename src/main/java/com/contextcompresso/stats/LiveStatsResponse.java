package com.contextcompresso.stats;

public record LiveStatsResponse(
        String sessionKey,
        int turns,
        TokenTotals tokens,
        double cacheHitRate,
        long effectiveInputTokens,
        LastTurn lastTurn,
        CompressionSummary compression
) {
    public record LastTurn(long input, long output, long cacheRead, double deltaVsPrev) {
    }

    public record CompressionSummary(long originalChars, long compressedChars, double ratio) {
    }

    public static LiveStatsResponse empty() {
        return new LiveStatsResponse(null, 0, TokenTotals.ZERO, 0.0, 0, null,
                new CompressionSummary(0, 0, 1.0));
    }
}
