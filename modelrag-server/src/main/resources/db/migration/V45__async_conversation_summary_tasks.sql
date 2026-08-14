ALTER TABLE kb_context_summary ADD COLUMN IF NOT EXISTS model_name VARCHAR(200);
ALTER TABLE kb_context_summary ADD COLUMN IF NOT EXISTS last_error TEXT;
ALTER TABLE kb_token_usage_daily ADD COLUMN IF NOT EXISTS summary_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE kb_token_usage_daily ADD COLUMN IF NOT EXISTS summary_calls BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS kb_context_summary_task (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES kb_conversation(id) ON DELETE CASCADE,
    user_id VARCHAR(128) NOT NULL,
    from_message_id BIGINT NOT NULL,
    to_message_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (conversation_id, to_message_id)
);
CREATE INDEX IF NOT EXISTS idx_summary_task_due
    ON kb_context_summary_task(status, next_retry_at, lease_until);
