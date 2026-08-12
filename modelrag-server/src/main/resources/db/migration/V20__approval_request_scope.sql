ALTER TABLE kb_approval_record ADD COLUMN IF NOT EXISTS requester_user_id VARCHAR(100);
ALTER TABLE kb_approval_record ADD COLUMN IF NOT EXISTS dataset_id BIGINT;
ALTER TABLE kb_approval_record ADD COLUMN IF NOT EXISTS conversation_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_approval_requester ON kb_approval_record(requester_user_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_approval_dataset ON kb_approval_record(dataset_id, create_time DESC);
