package com.contextcompresso.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for VatStash, the local code search feature (BM25 index over registered workspaces).
 * A blank {@code indexDir} means the index lives in {@code <parent of ccr.db-path>/vatstash}.
 */
@ConfigurationProperties(prefix = "contextcompresso.vatstash")
public record VatStashProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String indexDir,
        @DefaultValue("524288") int maxFileBytes,
        @DefaultValue("50000") int maxFilesPerWorkspace,
        @DefaultValue("20") int maxOpenWorkspaces,
        @DefaultValue("15") int minChunkLines,
        @DefaultValue("120") int maxChunkLines,
        @DefaultValue("10") int overlapLines,
        @DefaultValue("6000") int maxChunkChars,
        @DefaultValue("10000") long commitIntervalMs,
        @DefaultValue("2") long indexingPauseMs,
        @DefaultValue("15") int reconcileIntervalMinutes,
        @DefaultValue("8") int defaultMaxResults,
        @DefaultValue("30") int maxResultsCap,
        @DefaultValue("4000") int defaultMaxTokens,
        @DefaultValue("16000") int maxTokensCap,
        @DefaultValue("3") int maxChunksPerFile,
        @DefaultValue("90") int metricsRetentionDays,
        @DefaultValue("false") boolean storeQueryText,
        @DefaultValue("true") boolean mcpEnabled,
        @DefaultValue("true") boolean mcpAutoRegister
) {

    /** All defaults, with the index stored under the given directory. */
    public static VatStashProperties defaults(String indexDir) {
        return new VatStashProperties(true, indexDir, 524288, 50000, 20, 15, 120, 10, 6000, 10000L, 2L,
                15, 8, 30, 4000, 16000, 3, 90, false, true, true);
    }
}
