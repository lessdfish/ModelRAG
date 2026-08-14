ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS degraded_components JSONB NOT NULL DEFAULT '[]';
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS retrieval_latency_ms JSONB NOT NULL DEFAULT '{}';
