ALTER TABLE kb_document ALTER COLUMN active_index_version SET DEFAULT 0;
ALTER TABLE kb_index_outbox ADD COLUMN IF NOT EXISTS index_version BIGINT NOT NULL DEFAULT 1;
UPDATE kb_document d
SET active_index_version=0
WHERE NOT EXISTS (
    SELECT 1 FROM kb_chunk c
    WHERE c.document_id=d.id AND c.delete_time IS NULL AND c.version=d.active_index_version
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_context_summary_conversation_end
    ON kb_context_summary(conversation_id, to_message_id)
    WHERE to_message_id > 0;

CREATE INDEX IF NOT EXISTS idx_chunk_document_version_active
    ON kb_chunk(document_id, version) WHERE delete_time IS NULL;
