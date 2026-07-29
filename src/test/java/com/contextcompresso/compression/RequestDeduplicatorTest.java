package com.contextcompresso.compression;

import com.contextcompresso.config.CompressionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestDeduplicatorTest {

    @Test
    void sameHashWithinWindowReturnsCached() {
        RequestDeduplicator dedup = new RequestDeduplicator(
                new CompressionProperties(true, 20, 8000, true, 5, 3, 200, 2000),
                new SimpleMeterRegistry());
        String hash = dedup.hash("DEFAULT", "same body");

        var first = dedup.checkAndStore(hash, "DEFAULT", "result-1");
        assertThat(first).isEmpty();

        var second = dedup.checkAndStore(hash, "DEFAULT", "result-2");
        assertThat(second).contains("result-1");
    }

    @Test
    void differentHashExecutesNormally() {
        RequestDeduplicator dedup = new RequestDeduplicator(
                new CompressionProperties(true, 20, 8000, true, 5, 3, 200, 2000),
                new SimpleMeterRegistry());

        var first = dedup.checkAndStore(dedup.hash("DEFAULT", "body a"), "DEFAULT", "result-a");
        var second = dedup.checkAndStore(dedup.hash("DEFAULT", "body b"), "DEFAULT", "result-b");

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
    }

    @Test
    void expiredEntriesEvictedAfterWindow() throws InterruptedException {
        RequestDeduplicator dedup = new RequestDeduplicator(
                new CompressionProperties(true, 20, 8000, true, 5, 3, 200, 50),
                new SimpleMeterRegistry());
        String hash = dedup.hash("DEFAULT", "same body");

        dedup.checkAndStore(hash, "DEFAULT", "result-1");
        Thread.sleep(300);

        var afterExpiry = dedup.checkAndStore(hash, "DEFAULT", "result-2");
        assertThat(afterExpiry).isEmpty();
    }
}
