ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS final_prompt TEXT;
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS prompt_context TEXT;
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS context_max_tokens INT;
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS answer_source VARCHAR(40);
ALTER TABLE kb_retrieval_trace ADD COLUMN IF NOT EXISTS model_output TEXT;

CREATE INDEX IF NOT EXISTS idx_retrieval_trace_answer_source
    ON kb_retrieval_trace(answer_source, create_time DESC);
