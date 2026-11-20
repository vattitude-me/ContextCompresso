package com.contextcompresso.vatstash.index;

/** Manifest entry for one file: what was indexed and the stat values it was indexed at. */
public record FileState(
        String sha256,
        long mtime,
        long size,
        int fileTokens,
        int chunkCount,
        String skippedReason,
        long indexedAt
) {
    public FileState withStat(long newMtime, long newSize) {
        return new FileState(sha256, newMtime, newSize, fileTokens, chunkCount, skippedReason, indexedAt);
    }
}
