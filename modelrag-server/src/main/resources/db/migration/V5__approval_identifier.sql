ALTER TABLE kb_approval_record ADD COLUMN IF NOT EXISTS approval_id VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_approval_record_approval_id ON kb_approval_record(approval_id);
