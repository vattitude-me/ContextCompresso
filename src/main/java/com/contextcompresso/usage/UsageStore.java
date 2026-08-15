package com.contextcompresso.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UsageStore {

    private static final RowMapper<UsageRecord> ROW_MAPPER = (rs, rowNum) -> new UsageRecord(
            rs.getString("request_id"),
            rs.getString("session_key"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("cache_read_tokens"),
            rs.getLong("cache_write_tokens"),
            rs.getInt("original_chars"),
            rs.getInt("compressed_chars"),
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
            rs.getLong("created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public UsageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void store(UsageRecord record) {
        jdbcTemplate.update(
                "INSERT OR REPLACE INTO usage_records " +
                        "(request_id, session_key, provider, model, input_tokens, output_tokens, " +
                        "cache_read_tokens, cache_write_tokens, original_chars, compressed_chars, " +
                        "duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.requestId(), record.sessionKey(), record.provider(), record.model(),
                record.inputTokens(), record.outputTokens(), record.cacheReadTokens(),
                record.cacheWriteTokens(), record.originalChars(), record.compressedChars(),
                record.durationMs(), record.createdAt()
        );
    }

    public List<UsageRecord> findBySession(String sessionKey) {
        return jdbcTemplate.query(
                "SELECT * FROM usage_records WHERE session_key = ? ORDER BY created_at ASC",
                ROW_MAPPER, sessionKey);
    }

    public List<UsageRecord> findSince(long sinceEpochMs) {
        return jdbcTemplate.query(
                "SELECT * FROM usage_records WHERE created_at >= ? ORDER BY created_at ASC",
                ROW_MAPPER, sinceEpochMs);
    }

    public List<UsageRecord> topByEffectiveCost(long sinceEpochMs, int limit) {
        // effective-cost ranking is done in-memory by the caller (weights are configurable,
        // not fixed per row) — this just narrows the candidate set the DB has to return.
        return jdbcTemplate.query(
                "SELECT * FROM usage_records WHERE created_at >= ? " +
                        "ORDER BY (input_tokens + cache_read_tokens + cache_write_tokens + output_tokens) DESC " +
                        "LIMIT ?",
                ROW_MAPPER, sinceEpochMs, limit);
    }

    public int purgeExpired(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
        return jdbcTemplate.update("DELETE FROM usage_records WHERE created_at < ?", cutoff);
    }
}
