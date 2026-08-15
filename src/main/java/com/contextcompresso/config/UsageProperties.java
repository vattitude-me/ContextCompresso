package com.contextcompresso.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cache pricing weights used to compute "effective input tokens" — these are pricing
 * constants, not physics, and will drift as providers change their rate cards.
 */
@ConfigurationProperties(prefix = "contextcompresso.usage")
public record UsageProperties(
        @DefaultValue("0.1") double cacheReadWeight,
        @DefaultValue("1.25") double cacheWriteWeight,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("65536") int maxCapturedResponseBytes
) {
}
