package com.contextcompresso.vatstash.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SearchEventStore {

    private static final RowMapper<SearchEvent> ROW_MAPPER = (rs, n) -> new SearchEvent(
            rs.getString("id"),
            rs.getString("workspace_ids"),
            rs.getString("client"),
            rs.getInt("query_chars"),
            rs.getString("query_hash"),
            rs.getString("query_text"),
            rs.getInt("results"),
            rs.getInt("files"),
            rs.getLong("returned_tokens"),
            rs.getLong("source_file_tokens"),
            rs.getLong("took_ms"),
            rs.getString("freshness"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public SearchEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SearchEvent e) {
        jdbc.update("INSERT OR REPLACE INTO vatstash_search_events "
                        + "(id, workspace_ids, client, query_chars, query_hash, query_text, results, files, "
                        + "returned_tokens, source_file_tokens, took_ms, freshness, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.id(), e.workspaceIds(), e.client(), e.queryChars(), e.queryHash(), e.queryText(), e.results(),
                e.files(), e.returnedTokens(), e.sourceFileTokens(), e.tookMs(), e.freshness(), e.createdAt());
    }

    public List<SearchEvent> findSince(long sinceEpochMs) {
        return jdbc.query("SELECT * FROM vatstash_search_events WHERE created_at >= ? ORDER BY created_at ASC",
                ROW_MAPPER, sinceEpochMs);
    }

    public int purgeExpired(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
        return jdbc.update("DELETE FROM vatstash_search_events WHERE created_at < ?", cutoff);
    }
}
