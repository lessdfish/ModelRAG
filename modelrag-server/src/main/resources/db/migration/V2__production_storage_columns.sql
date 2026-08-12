ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS source_content TEXT;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS delete_time TIMESTAMP;
ALTER TABLE kb_dataset ADD COLUMN IF NOT EXISTS delete_time TIMESTAMP;
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS delete_time TIMESTAMP;
ALTER TABLE kb_index_outbox ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_document_dataset_status ON kb_document(dataset_id, index_status) WHERE delete_time IS NULL;
CREATE INDEX IF NOT EXISTS idx_chunk_dataset_active ON kb_chunk(dataset_id) WHERE delete_time IS NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_due ON kb_index_outbox(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON kb_chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);
