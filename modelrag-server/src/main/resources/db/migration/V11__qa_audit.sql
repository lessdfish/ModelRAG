CREATE TABLE IF NOT EXISTS kb_qa_audit (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    dataset_id BIGINT NOT NULL,
    conversation_id BIGINT,
    query TEXT NOT NULL,
    answer TEXT NOT NULL,
    citations JSONB DEFAULT '[]',
    confidence DECIMAL(3,2),
    refused BOOLEAN DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_qa_audit_dataset_time ON kb_qa_audit(dataset_id, create_time DESC);
