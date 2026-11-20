CREATE TABLE IF NOT EXISTS ccr_entries (
    id          TEXT PRIMARY KEY,
    request_id  TEXT NOT NULL,
    message_idx INTEGER NOT NULL,
    content_key TEXT NOT NULL,
    original    TEXT NOT NULL,
    compressed  TEXT NOT NULL,
    char_delta  INTEGER NOT NULL,
    created_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ccr_request ON ccr_entries(request_id);
CREATE INDEX IF NOT EXISTS idx_ccr_created ON ccr_entries(created_at);

CREATE TABLE IF NOT EXISTS usage_records (
    request_id         TEXT PRIMARY KEY,
    session_key        TEXT,
    provider           TEXT NOT NULL,
    model              TEXT,
    input_tokens       INTEGER NOT NULL DEFAULT 0,
    output_tokens      INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens  INTEGER NOT NULL DEFAULT 0,
    cache_write_tokens INTEGER NOT NULL DEFAULT 0,
    original_chars     INTEGER NOT NULL DEFAULT 0,
    compressed_chars   INTEGER NOT NULL DEFAULT 0,
    duration_ms        INTEGER,
    created_at         INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_created ON usage_records(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_session ON usage_records(session_key);

CREATE TABLE IF NOT EXISTS vatstash_workspaces (
    workspace_id  TEXT PRIMARY KEY,
    root          TEXT NOT NULL,
    settings_json TEXT,
    registered_at INTEGER NOT NULL,
    last_seen_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS vatstash_search_events (
    id                 TEXT PRIMARY KEY,
    workspace_ids      TEXT NOT NULL,
    client             TEXT NOT NULL,
    query_chars        INTEGER NOT NULL,
    query_hash         TEXT NOT NULL,
    query_text         TEXT,
    results            INTEGER NOT NULL,
    files              INTEGER NOT NULL,
    returned_tokens    INTEGER NOT NULL,
    source_file_tokens INTEGER NOT NULL,
    took_ms            INTEGER NOT NULL,
    freshness          TEXT NOT NULL,
    created_at         INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vatstash_events_created ON vatstash_search_events(created_at);
