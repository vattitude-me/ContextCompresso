package com.contextcompresso.ccr;

public record CcrEntry(
        String id,
        String requestId,
        int messageIdx,
        String contentKey,
        String original,
        String compressed,
        int charDelta,
        long createdAt
) {
}
