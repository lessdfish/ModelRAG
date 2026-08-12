CREATE TABLE IF NOT EXISTS kb_feedback (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    dataset_id BIGINT NOT NULL,
    user_id VARCHAR(100),
    rating VARCHAR(10) NOT NULL,
    comment TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_feedback_dataset_time ON kb_feedback(dataset_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_trace ON kb_feedback(trace_id);
