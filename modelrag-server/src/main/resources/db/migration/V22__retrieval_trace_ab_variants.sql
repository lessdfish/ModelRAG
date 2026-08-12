ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS ab_variants JSONB DEFAULT '[]';
