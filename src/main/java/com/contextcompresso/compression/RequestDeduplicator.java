package com.contextcompresso.compression;

import com.contextcompresso.config.CompressionProperties;
import com.contextcompresso.util.HashUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Detects duplicate requests fired within a short window (rapid keystrokes / IDE retry
 * logic) so the pipeline can short-circuit and reuse an in-flight/recent result instead
 * of double-billing and double-latency.
 */
@Component
public class RequestDeduplicator {

    private final Cache<String, Object> cache;
    private final MeterRegistry meterRegistry;

    public RequestDeduplicator(CompressionProperties properties, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(Math.max(properties.dedupWindowMs(), 1)))
                .maximumSize(10_000)
                .build();
    }

    public String hash(String provider, String body) {
        return HashUtil.sha256Hex(provider + "|" + body);
    }

    /**
     * Returns the cached result if a request with this hash was already seen inside the
     * dedup window, otherwise records the new result under this hash and returns empty.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> checkAndStore(String hash, String provider, T result) {
        Object existing = cache.asMap().putIfAbsent(hash, result);
        if (existing != null) {
            meterRegistry.counter("cc.dedup.hits", "provider", provider).increment();
            return Optional.of((T) existing);
        }
        return Optional.empty();
    }

    public Optional<Object> peek(String hash) {
        return Optional.ofNullable(cache.getIfPresent(hash));
    }

    public long size() {
        return cache.estimatedSize();
    }
}
