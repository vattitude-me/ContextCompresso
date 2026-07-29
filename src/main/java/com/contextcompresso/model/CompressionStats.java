package com.contextcompresso.model;

public record CompressionStats(
        int originalChars,
        int compressedChars,
        double ratio
) {

    public static CompressionStats of(int originalChars, int compressedChars) {
        double ratio = originalChars == 0 ? 1.0 : (double) compressedChars / (double) originalChars;
        return new CompressionStats(originalChars, compressedChars, ratio);
    }
}
