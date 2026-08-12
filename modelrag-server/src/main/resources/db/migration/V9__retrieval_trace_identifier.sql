ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_retrieval_trace_id ON kb_retrieval_trace(trace_id) WHERE trace_id IS NOT NULL;
