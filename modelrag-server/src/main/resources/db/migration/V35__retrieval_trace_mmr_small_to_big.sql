ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS mmr_results JSONB DEFAULT '[]';
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS small_to_big_context JSONB DEFAULT '[]';
