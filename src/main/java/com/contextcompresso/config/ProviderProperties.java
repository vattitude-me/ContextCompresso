package com.contextcompresso.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.util.List;

/**
 * Binds contextcompresso.providers.{copilot,claude,default} from application.yml.
 * The compression sub-block fields vary slightly per provider, so a single flexible
 * record with all optional fields (relaxed binding leaves unset ones at their default)
 * is used rather than three divergent shapes.
 *
 * "default" is a Java reserved word, so the record component is named defaultProvider
 * and re-mapped to the "default" YAML key via @Name.
 */
@ConfigurationProperties(prefix = "contextcompresso.providers")
public record ProviderProperties(
        ProviderBlock copilot,
        ProviderBlock claude,
        @Name("default") ProviderBlock defaultProvider
) {

    public record ProviderBlock(
            boolean enabled,
            String baseUrl,
            String apiStyle,
            String authType,
            long maxContextTokens,
            String tokenEstimator,
            List<String> forwardHeaders,
            CompressionBlock compression
    ) {
    }

    public record CompressionBlock(
            boolean codeCompressionEnabled,
            boolean stripCopilotSystemPrompts,
            boolean aggressiveCodeStrip,
            boolean cacheAlignmentEnabled,
            boolean preserveCacheControlBlocks
    ) {
    }
}
