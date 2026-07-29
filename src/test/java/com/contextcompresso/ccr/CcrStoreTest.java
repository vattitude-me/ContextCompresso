package com.contextcompresso.ccr;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CcrStoreTest {

    private JdbcTemplate jdbcTemplate;
    private CcrStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("ccr-test.db").toFile();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(JDBC.class);
        dataSource.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ccr_entries (" +
                "id TEXT PRIMARY KEY, request_id TEXT NOT NULL, message_idx INTEGER NOT NULL, " +
                "content_key TEXT NOT NULL, original TEXT NOT NULL, compressed TEXT NOT NULL, " +
                "char_delta INTEGER NOT NULL, created_at INTEGER NOT NULL)");
        store = new CcrStore(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ccr_entries");
    }

    private CcrEntry sampleEntry(String requestId, int idx) {
        String contentKey = "content[" + idx + "]";
        String id = CcrStore.computeId(requestId, idx, contentKey);
        return new CcrEntry(id, requestId, idx, contentKey, "original text", "compressed", -5,
                System.currentTimeMillis());
    }

    @Test
    void storeAndFindById() {
        CcrEntry entry = sampleEntry("req-1", 0);
        String id = store.store(entry);

        Optional<CcrEntry> found = store.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().original()).isEqualTo("original text");
    }

    @Test
    void storeAllAndFindByRequestId() {
        String requestId = "req-2";
        List<CcrEntry> entries = List.of(sampleEntry(requestId, 0), sampleEntry(requestId, 1));
        store.storeAll(requestId, entries);

        List<CcrEntry> found = store.findByRequestId(requestId);
        assertThat(found).hasSize(2);
    }

    @Test
    void insertOrReplaceIsIdempotent() {
        CcrEntry entry = sampleEntry("req-3", 0);
        store.store(entry);
        CcrEntry updated = new CcrEntry(entry.id(), entry.requestId(), entry.messageIdx(),
                entry.contentKey(), entry.original(), "new compressed value", -10, entry.createdAt());
        store.store(updated);

        Optional<CcrEntry> found = store.findById(entry.id());
        assertThat(found).isPresent();
        assertThat(found.get().compressed()).isEqualTo("new compressed value");

        List<CcrEntry> all = store.findByRequestId("req-3");
        assertThat(all).hasSize(1);
    }

    @Test
    void purgeExpiredRemovesOldEntries() {
        long oldTimestamp = System.currentTimeMillis() - (40L * 24 * 60 * 60 * 1000);
        CcrEntry oldEntry = new CcrEntry(CcrStore.computeId("req-4", 0, "content[0]"), "req-4", 0,
                "content[0]", "orig", "comp", 0, oldTimestamp);
        CcrEntry recentEntry = sampleEntry("req-4", 1);
        store.store(oldEntry);
        store.store(recentEntry);

        int deleted = store.purgeExpired(30);
        assertThat(deleted).isEqualTo(1);

        List<CcrEntry> remaining = store.findByRequestId("req-4");
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).messageIdx()).isEqualTo(1);
    }
}
