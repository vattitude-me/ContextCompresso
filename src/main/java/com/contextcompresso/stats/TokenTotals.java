package com.contextcompresso.stats;

import com.contextcompresso.usage.UsageRecord;

import java.util.List;

public record TokenTotals(
        long input,
        long output,
        long cacheRead,
        long cacheWrite
) {
    public static final TokenTotals ZERO = new TokenTotals(0, 0, 0, 0);

    public static TokenTotals sumOf(List<UsageRecord> records) {
        long input = 0, output = 0, cacheRead = 0, cacheWrite = 0;
        for (UsageRecord r : records) {
            input += r.inputTokens();
            output += r.outputTokens();
            cacheRead += r.cacheReadTokens();
            cacheWrite += r.cacheWriteTokens();
        }
        return new TokenTotals(input, output, cacheRead, cacheWrite);
    }

    public long effectiveInput(double cacheReadWeight, double cacheWriteWeight) {
        return input + Math.round(cacheRead * cacheReadWeight) + Math.round(cacheWrite * cacheWriteWeight);
    }

    /** Fraction of prompt tokens (input + cacheRead) that were served from cache. */
    public double cacheHitRate() {
        long promptTokens = input + cacheRead;
        return promptTokens == 0 ? 0.0 : (double) cacheRead / (double) promptTokens;
    }
}
