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
