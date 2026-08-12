ALTER TABLE kb_conversation ADD COLUMN IF NOT EXISTS user_id VARCHAR(100) NOT NULL DEFAULT 'global';
CREATE INDEX IF NOT EXISTS idx_conversation_user_active ON kb_conversation(user_id,archive_time,update_time DESC);
