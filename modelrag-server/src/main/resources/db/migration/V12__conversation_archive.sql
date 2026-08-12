ALTER TABLE kb_conversation ADD COLUMN IF NOT EXISTS archive_time TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_conversation_active ON kb_conversation(archive_time, update_time DESC);
