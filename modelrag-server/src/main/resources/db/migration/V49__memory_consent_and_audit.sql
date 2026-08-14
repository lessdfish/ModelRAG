ALTER TABLE kb_memory_setting ALTER COLUMN enabled SET DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS kb_user_memory_audit (
    id BIGSERIAL PRIMARY KEY,
    memory_id BIGINT,
    user_id VARCHAR(100) NOT NULL,
    dataset_id BIGINT,
    scope VARCHAR(30) NOT NULL,
    memory_type VARCHAR(80),
    memory_key VARCHAR(200),
    content TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_conversation_id BIGINT,
    source_message_id BIGINT,
    action VARCHAR(30) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_memory_audit_user_time
    ON kb_user_memory_audit(user_id, create_time DESC);
