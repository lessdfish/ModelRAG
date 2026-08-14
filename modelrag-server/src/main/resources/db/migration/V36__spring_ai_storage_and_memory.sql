CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE kb_dataset ALTER COLUMN embedding_model SET DEFAULT 'Qwen3-Embedding-0.6B';
UPDATE kb_dataset SET embedding_model='Qwen3-Embedding-0.6B' WHERE embedding_model ILIKE '%bge-large-zh-v1.5%';
ALTER TABLE kb_dataset ADD COLUMN IF NOT EXISTS embedding_profile_version VARCHAR(80) NOT NULL DEFAULT 'qwen3-embedding-0.6b-1024-v1';
ALTER TABLE kb_dataset ADD COLUMN IF NOT EXISTS embedding_dimensions INT NOT NULL DEFAULT 1024;

ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS source_object_key VARCHAR(1000);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS artifact_object_key VARCHAR(1000);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS active_index_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS index_state VARCHAR(30) NOT NULL DEFAULT 'BUILDING';
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

CREATE TABLE IF NOT EXISTS kb_refresh_token (
    token_id UUID PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL REFERENCES kb_user_account(user_id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_user ON kb_refresh_token(user_id, expires_at);

ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS dataset_id BIGINT REFERENCES kb_dataset(id);
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS scope VARCHAR(30) NOT NULL DEFAULT 'USER_GLOBAL';
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS memory_key VARCHAR(200);
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS source_conversation_id BIGINT;
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS source_message_id BIGINT;
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS embedding_profile_version VARCHAR(80) DEFAULT 'qwen3-embedding-0.6b-1024-v1';
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMP;
ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE kb_user_memory ADD CONSTRAINT uq_user_memory_scope_key UNIQUE (user_id, dataset_id, scope, memory_key);

ALTER TABLE kb_index_outbox ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
ALTER TABLE kb_index_outbox ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(200);
ALTER TABLE kb_index_outbox ADD COLUMN IF NOT EXISTS dead_letter BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_outbox_claim ON kb_index_outbox(status, lease_until, next_retry_at);

ALTER TABLE kb_conversation ADD COLUMN IF NOT EXISTS user_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_conversation_user_updated ON kb_conversation(user_id, update_time DESC);
ALTER TABLE kb_context_summary ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(80) NOT NULL DEFAULT 'conversation-summary-v1';
ALTER TABLE kb_context_summary ADD COLUMN IF NOT EXISTS token_usage BIGINT NOT NULL DEFAULT 0;
ALTER TABLE kb_context_summary ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'READY';
