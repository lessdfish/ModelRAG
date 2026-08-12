CREATE TABLE IF NOT EXISTS kb_context_summary (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES kb_conversation(id),
    summary_type VARCHAR(20) NOT NULL DEFAULT 'HISTORY',
    from_message_id BIGINT NOT NULL DEFAULT 0,
    to_message_id BIGINT NOT NULL DEFAULT 0,
    summary TEXT NOT NULL,
    token_count INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_context_summary_conversation ON kb_context_summary(conversation_id,create_time DESC);
