ALTER TABLE kb_qa_audit ADD COLUMN IF NOT EXISTS user_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_qa_audit_user_time ON kb_qa_audit(user_id, create_time DESC);
