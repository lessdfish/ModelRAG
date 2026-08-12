ALTER TABLE kb_user_memory ADD COLUMN IF NOT EXISTS embedding vector(1024);
CREATE INDEX IF NOT EXISTS idx_user_memory_embedding ON kb_user_memory USING hnsw (embedding vector_cosine_ops);
