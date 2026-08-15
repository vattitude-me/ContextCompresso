package com.contextcompresso.usage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.sqlite.JDBC;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsageStoreTest {

    private JdbcTemplate jdbcTemplate;
    private UsageStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("usage-test.db").toFile();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(JDBC.class);
        dataSource.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS usage_records (" +
                "request_id TEXT PRIMARY KEY, session_key TEXT, provider TEXT NOT NULL, model TEXT, " +
                "input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, " +
                "cache_read_tokens INTEGER NOT NULL DEFAULT 0, cache_write_tokens INTEGER NOT NULL DEFAULT 0, " +
                "original_chars INTEGER NOT NULL DEFAULT 0, compressed_chars INTEGER NOT NULL DEFAULT 0, " +
                "duration_ms INTEGER, created_at INTEGER NOT NULL)");
        store = new UsageStore(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS usage_records");
    }

    private UsageRecord sample(String requestId, String sessionKey, long createdAt) {
        return new UsageRecord(requestId, sessionKey, "CLAUDE", "claude-opus-5",
                100, 20, 900, 10, 5000, 3000, 42L, createdAt);
    }

    @Test
    void storeAndFindBySession() {
        store.store(sample("req-1", "sess-a", 1000L));
        store.store(sample("req-2", "sess-a", 2000L));
        store.store(sample("req-3", "sess-b", 1500L));

        List<UsageRecord> sessionA = store.findBySession("sess-a");
        assertThat(sessionA).hasSize(2);
        assertThat(sessionA.get(0).requestId()).isEqualTo("req-1");
        assertThat(sessionA.get(1).requestId()).isEqualTo("req-2");
    }

    @Test
    void findSinceFiltersByTimestamp() {
        store.store(sample("req-1", "sess-a", 1000L));
        store.store(sample("req-2", "sess-a", 5000L));

        List<UsageRecord> recent = store.findSince(3000L);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).requestId()).isEqualTo("req-2");
    }

    @Test
    void insertOrReplaceIsIdempotentByRequestId() {
        store.store(sample("req-1", "sess-a", 1000L));
        UsageRecord updated = new UsageRecord("req-1", "sess-a", "CLAUDE", "claude-opus-5",
                999, 999, 999, 999, 1, 1, 1L, 1000L);
        store.store(updated);

        List<UsageRecord> all = store.findSince(0L);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).inputTokens()).isEqualTo(999);
    }

    @Test
    void purgeExpiredRemovesOldRecords() {
        long oldTimestamp = System.currentTimeMillis() - (40L * 24 * 60 * 60 * 1000);
        store.store(sample("req-old", "sess-a", oldTimestamp));
        store.store(sample("req-new", "sess-a", System.currentTimeMillis()));

        int deleted = store.purgeExpired(30);
        assertThat(deleted).isEqualTo(1);
        assertThat(store.findSince(0L)).hasSize(1);
    }

    @Test
    void effectiveInputTokensAppliesCacheWeights() {
        UsageRecord record = sample("req-1", "sess-a", 1000L);
        long effective = record.effectiveInputTokens(0.1, 1.25);
        // 100 input + (900 * 0.1 = 90) + (10 * 1.25 = 12.5 -> rounds to 13) = 203
        assertThat(effective).isEqualTo(203);
    }
}
