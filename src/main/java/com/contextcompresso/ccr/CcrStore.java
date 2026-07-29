package com.contextcompresso.ccr;

import com.contextcompresso.util.HashUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class CcrStore {

    private static final RowMapper<CcrEntry> ROW_MAPPER = (rs, rowNum) -> new CcrEntry(
            rs.getString("id"),
            rs.getString("request_id"),
            rs.getInt("message_idx"),
            rs.getString("content_key"),
            rs.getString("original"),
            rs.getString("compressed"),
            rs.getInt("char_delta"),
            rs.getLong("created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public CcrStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static String computeId(String requestId, int messageIdx, String contentKey) {
        return HashUtil.sha256Hex(requestId + messageIdx + contentKey);
    }

    public String store(CcrEntry entry) {
        String id = entry.id() != null ? entry.id()
                : computeId(entry.requestId(), entry.messageIdx(), entry.contentKey());
        jdbcTemplate.update(
                "INSERT OR REPLACE INTO ccr_entries " +
                        "(id, request_id, message_idx, content_key, original, compressed, char_delta, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, entry.requestId(), entry.messageIdx(), entry.contentKey(),
                entry.original(), entry.compressed(), entry.charDelta(), entry.createdAt()
        );
        return id;
    }

    public List<String> storeAll(String requestId, List<CcrEntry> entries) {
        List<String> ids = new ArrayList<>(entries.size());
        for (CcrEntry entry : entries) {
            CcrEntry withRequestId = entry.requestId() != null && entry.requestId().equals(requestId)
                    ? entry
                    : new CcrEntry(entry.id(), requestId, entry.messageIdx(), entry.contentKey(),
                    entry.original(), entry.compressed(), entry.charDelta(), entry.createdAt());
            ids.add(store(withRequestId));
        }
        return ids;
    }

    public Optional<CcrEntry> findById(String id) {
        List<CcrEntry> results = jdbcTemplate.query(
                "SELECT * FROM ccr_entries WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    public List<CcrEntry> findByRequestId(String requestId) {
        return jdbcTemplate.query(
                "SELECT * FROM ccr_entries WHERE request_id = ? ORDER BY message_idx ASC",
                ROW_MAPPER, requestId);
    }

    public int purgeExpired(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
        return jdbcTemplate.update("DELETE FROM ccr_entries WHERE created_at < ?", cutoff);
    }
}
