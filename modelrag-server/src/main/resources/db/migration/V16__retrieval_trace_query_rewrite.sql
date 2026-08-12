ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS query_rewritten TEXT;
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS search_queries JSONB DEFAULT '[]';
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS rerank_query TEXT;
