ALTER TABLE kb_agent_execution
    ADD COLUMN IF NOT EXISTS mode VARCHAR(30),
    ADD COLUMN IF NOT EXISTS goal TEXT,
    ADD COLUMN IF NOT EXISTS current_step INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_steps INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS state_version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS budget_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS last_checkpoint_seq BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(160),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS error_msg VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_execution_recovery
    ON kb_agent_execution(status, lease_until, update_time);

CREATE TABLE IF NOT EXISTS kb_agent_checkpoint (
    id BIGSERIAL PRIMARY KEY,
    execution_id VARCHAR(80) NOT NULL
        REFERENCES kb_agent_execution(execution_id) ON DELETE CASCADE,
    checkpoint_seq BIGINT NOT NULL,
    state_version INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    state_json JSONB NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(execution_id, checkpoint_seq)
);

CREATE INDEX IF NOT EXISTS idx_agent_checkpoint_latest
    ON kb_agent_checkpoint(execution_id, checkpoint_seq DESC);
