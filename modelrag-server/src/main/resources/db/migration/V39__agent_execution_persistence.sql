CREATE TABLE IF NOT EXISTS kb_agent_execution (
    execution_id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    conversation_id BIGINT,
    status VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_agent_execution_user ON kb_agent_execution(user_id, update_time DESC);
