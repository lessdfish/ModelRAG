ALTER TABLE kb_dataset ADD COLUMN IF NOT EXISTS routing_embedding vector(1024);

CREATE INDEX IF NOT EXISTS idx_dataset_routing_embedding
    ON kb_dataset USING hnsw (routing_embedding vector_cosine_ops)
    WHERE delete_time IS NULL;
