package com.contextcompresso.provider;

import java.util.List;

/**
 * Resolved, provider-specific configuration used throughout the compression/proxy pipeline.
 */
public record ProviderConfig(
        Provider name,
        String baseUrl,
        String apiStyle,
        String authType,
        long maxContextTokens,
        String tokenEstimator,
        List<String> forwardHeaders,
        boolean codeCompressionEnabled,
        boolean stripCopilotSystemPrompts,
        boolean aggressiveCodeStrip,
        boolean cacheAlignmentEnabled,
        boolean preserveCacheControlBlocks
) {
}
