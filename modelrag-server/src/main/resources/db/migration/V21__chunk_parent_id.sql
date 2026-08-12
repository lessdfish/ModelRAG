ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS parent_chunk_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_chunk_parent ON kb_chunk(parent_chunk_id) WHERE delete_time IS NULL;
