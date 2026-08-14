CREATE UNIQUE INDEX IF NOT EXISTS uq_index_outbox_idempotency
    ON kb_index_outbox(idempotency_key) WHERE idempotency_key IS NOT NULL;
ALTER TABLE kb_index_outbox ADD COLUMN IF NOT EXISTS index_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS active_index_version BIGINT NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_document_active_index
    ON kb_document(dataset_id, active_index_version, index_state) WHERE delete_time IS NULL;
