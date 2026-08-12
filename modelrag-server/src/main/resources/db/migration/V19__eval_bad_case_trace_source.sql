ALTER TABLE kb_eval_dataset ADD COLUMN IF NOT EXISTS source_trace_id VARCHAR(64);
ALTER TABLE kb_eval_dataset ADD COLUMN IF NOT EXISTS failure_stage VARCHAR(40);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_trace ON kb_eval_dataset(source_trace_id);
