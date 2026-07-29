package com.contextcompresso.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contextcompresso.compression")
public record CompressionProperties(
        boolean enabled,
        int maxArrayElements,
        int maxMessageChars,
        boolean textTruncationEnabled,
        int truncationHeadSentences,
        int truncationTailSentences,
        int minCompressChars,
        long dedupWindowMs
) {
}
