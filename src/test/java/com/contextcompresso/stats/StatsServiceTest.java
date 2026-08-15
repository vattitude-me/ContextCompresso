package com.contextcompresso.stats;

import com.contextcompresso.config.UsageProperties;
import com.contextcompresso.usage.UsageRecord;
import com.contextcompresso.usage.UsageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.sqlite.JDBC;

import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatsServiceTest {

    private JdbcTemplate jdbcTemplate;
    private UsageStore usageStore;
    private StatsService statsService;
    private final UsageProperties properties = new UsageProperties(0.1, 1.25, 30, 65536);

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("stats-test.db").toFile();
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
        usageStore = new UsageStore(jdbcTemplate);
        statsService = new StatsService(usageStore, properties);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS usage_records");
    }

    private UsageRecord record(String requestId, String sessionKey, String model, long input, long output,
                                long cacheRead, long cacheWrite, int origChars, int compChars, long createdAt) {
        return new UsageRecord(requestId, sessionKey, "CLAUDE", model, input, output, cacheRead, cacheWrite,
                origChars, compChars, 10L, createdAt);
    }

    @Test
    void liveStatsForSessionReturnsEmptyWhenNoRecords() {
        LiveStatsResponse response = statsService.liveStatsForSession("nonexistent");
        assertThat(response.turns()).isZero();
        assertThat(response.sessionKey()).isNull();
    }

    @Test
    void liveStatsAggregatesTurnsAndComputesCacheHitRate() {
        usageStore.store(record("req-1", "sess-a", "claude-opus-5", 100, 20, 0, 0, 1000, 800, 1000L));
        usageStore.store(record("req-2", "sess-a", "claude-opus-5", 50, 30, 900, 10, 1000, 900, 2000L));

        LiveStatsResponse response = statsService.liveStatsForSession("sess-a");

        assertThat(response.turns()).isEqualTo(2);
        assertThat(response.tokens().input()).isEqualTo(150);
        assertThat(response.tokens().output()).isEqualTo(50);
        assertThat(response.tokens().cacheRead()).isEqualTo(900);
        // cacheHitRate = cacheRead / (input + cacheRead) = 900 / (150 + 900)
        assertThat(response.cacheHitRate()).isCloseTo(900.0 / 1050.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(response.compression().originalChars()).isEqualTo(2000);
        assertThat(response.compression().compressedChars()).isEqualTo(1700);
    }

    @Test
    void lastTurnDeltaReflectsGrowthFromPreviousTurn() {
        // effective(turn1) = 100, effective(turn2) = 200 -> +100%
        usageStore.store(record("req-1", "sess-a", "claude-opus-5", 100, 0, 0, 0, 100, 100, 1000L));
        usageStore.store(record("req-2", "sess-a", "claude-opus-5", 200, 0, 0, 0, 100, 100, 2000L));

        LiveStatsResponse response = statsService.liveStatsForSession("sess-a");

        assertThat(response.lastTurn().deltaVsPrev()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void singleTurnSessionHasZeroDelta() {
        usageStore.store(record("req-1", "sess-a", "claude-opus-5", 100, 0, 0, 0, 100, 100, 1000L));

        LiveStatsResponse response = statsService.liveStatsForSession("sess-a");

        assertThat(response.lastTurn().deltaVsPrev()).isZero();
    }

    @Test
    void topCostsRanksByEffectiveInputDescending() {
        long now = System.currentTimeMillis();
        usageStore.store(record("cheap", "sess-a", "claude-opus-5", 10, 5, 0, 0, 100, 100, now));
        usageStore.store(record("expensive", "sess-a", "claude-opus-5", 5000, 100, 2000, 0, 100, 100, now));
        usageStore.store(record("medium", "sess-a", "claude-opus-5", 500, 50, 200, 0, 100, 100, now));

        List<TopCostEntry> top = statsService.topCosts(10);

        assertThat(top).hasSize(3);
        assertThat(top.get(0).requestId()).isEqualTo("expensive");
        assertThat(top.get(1).requestId()).isEqualTo("medium");
        assertThat(top.get(2).requestId()).isEqualTo("cheap");
    }

    @Test
    void todayStatsExcludesRecordsFromPriorDays() {
        long startOfToday = ZonedDateTime.now(ZoneId.systemDefault())
                .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long yesterday = startOfToday - (2L * 60 * 60 * 1000);
        long todayMorning = startOfToday + (60L * 60 * 1000);

        usageStore.store(record("req-yesterday", "sess-a", "claude-opus-5", 100, 0, 0, 0, 100, 100, yesterday));
        usageStore.store(record("req-today", "sess-a", "claude-opus-5", 200, 0, 0, 0, 100, 100, todayMorning));

        TodayStatsResponse response = statsService.todayStats();

        assertThat(response.requestCount()).isEqualTo(1);
        assertThat(response.totals().input()).isEqualTo(200);
    }

    @Test
    void todayStatsGroupsByModel() {
        long now = System.currentTimeMillis();
        usageStore.store(record("req-1", "sess-a", "claude-opus-5", 1000, 0, 0, 0, 100, 100, now));
        usageStore.store(record("req-2", "sess-a", "claude-haiku", 100, 0, 0, 0, 100, 100, now));

        TodayStatsResponse response = statsService.todayStats();

        assertThat(response.byModel()).hasSize(2);
        assertThat(response.byModel().get(0).model()).isEqualTo("claude-opus-5");
        assertThat(response.byModel().get(0).requestCount()).isEqualTo(1);
    }
}
