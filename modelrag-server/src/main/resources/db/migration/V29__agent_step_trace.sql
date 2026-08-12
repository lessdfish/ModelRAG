CREATE TABLE IF NOT EXISTS kb_agent_step_trace (
    id BIGSERIAL PRIMARY KEY,
    execution_id VARCHAR(80) NOT NULL,
    step_index INT NOT NULL,
    phase VARCHAR(40) NOT NULL,
    message TEXT,
    data JSONB DEFAULT '{}',
    status VARCHAR(30) NOT NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_step_trace_execution
    ON kb_agent_step_trace(execution_id, step_index);

CREATE INDEX IF NOT EXISTS idx_agent_step_trace_time
    ON kb_agent_step_trace(create_time DESC);
