ALTER TABLE kb_message ADD COLUMN IF NOT EXISTS trace_id VARCHAR(80);
ALTER TABLE kb_message ADD COLUMN IF NOT EXISTS mode VARCHAR(20);
ALTER TABLE kb_message ADD COLUMN IF NOT EXISTS dataset_name VARCHAR(200);
CREATE INDEX IF NOT EXISTS idx_message_trace_id ON kb_message(trace_id);
