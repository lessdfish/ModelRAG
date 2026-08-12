ALTER TABLE kb_qa_audit ADD COLUMN IF NOT EXISTS mode VARCHAR(20) DEFAULT 'rag';
CREATE INDEX IF NOT EXISTS idx_qa_audit_mode_time ON kb_qa_audit(mode, create_time DESC);
