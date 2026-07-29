package com.contextcompresso.provider;

import com.contextcompresso.config.ProviderProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
public class ProviderRouter {

    private final ProviderDetector detector;
    private final ProviderProperties properties;

    public ProviderRouter(ProviderDetector detector, ProviderProperties properties) {
        this.detector = detector;
        this.properties = properties;
    }

    /**
     * Resolves the ProviderConfig for a request. defaultProviderName is currently accepted
     * for API-shape compatibility with the plan (e.g. "openai"/"anthropic" hints from the
     * calling endpoint) but detection always falls back to the configured DEFAULT provider
     * block when no stronger signal matches.
     */
    public ProviderConfig resolve(ServerHttpRequest request, String defaultProviderName) {
        Provider provider = detector.detect(request);
        return toConfig(provider);
    }

    public ProviderConfig toConfig(Provider provider) {
        ProviderProperties.ProviderBlock block = switch (provider) {
            case COPILOT -> properties.copilot();
            case CLAUDE -> properties.claude();
            case DEFAULT -> properties.defaultProvider();
        };
        ProviderProperties.CompressionBlock compression = block.compression();
        return new ProviderConfig(
                provider,
                block.baseUrl(),
                block.apiStyle(),
                block.authType(),
                block.maxContextTokens(),
                block.tokenEstimator(),
                block.forwardHeaders(),
                compression != null && compression.codeCompressionEnabled(),
                compression != null && compression.stripCopilotSystemPrompts(),
                compression != null && compression.aggressiveCodeStrip(),
                compression != null && compression.cacheAlignmentEnabled(),
                compression != null && compression.preserveCacheControlBlocks()
        );
    }
}
