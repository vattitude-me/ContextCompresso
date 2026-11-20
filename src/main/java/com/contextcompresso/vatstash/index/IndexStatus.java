package com.contextcompresso.vatstash.index;

/**
 * Wire shape of the status of one workspace index (see the VatStash HTTP contract).
 * {@code state} is building, updating, ready or error; {@code freshness} is fresh only when ready.
 * {@code lastFullScanAt} and {@code lastUpdateAt} are epoch milliseconds, 0 when it has not happened.
 */
public record IndexStatus(
        String workspaceId,
        String root,
        String state,
        String freshness,
        int filesIndexed,
        int filesSkipped,
        int chunksIndexed,
        int pendingFiles,
        Progress progress,
        long indexBytes,
        long lastFullScanAt,
        long lastUpdateAt,
        boolean truncated,
        String lastError
) {
    public record Progress(int done, int total) {
    }
}
