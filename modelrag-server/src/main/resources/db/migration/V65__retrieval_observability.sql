-- G11 forward migration. V61-V63 are reserved historical plan slots; V64 is already shipped.
ALTER TABLE kb_retrieval_trace
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS execution_id VARCHAR(80),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    ADD COLUMN IF NOT EXISTS total_actions INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_evidence INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS profile VARCHAR(100),
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS kb_retrieval_action (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    step_no INT NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    request_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    candidate_count INT NOT NULL DEFAULT 0,
    degraded BOOLEAN NOT NULL DEFAULT FALSE,
    degraded_components JSONB NOT NULL DEFAULT '[]'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_retrieval_action_request_bounded CHECK (octet_length(request_summary::text) <= 8192),
    CONSTRAINT ck_retrieval_action_result_bounded CHECK (octet_length(result_summary::text) <= 8192),
    CONSTRAINT ck_retrieval_action_degraded_bounded CHECK (octet_length(degraded_components::text) <= 8192)
);

CREATE INDEX IF NOT EXISTS idx_retrieval_action_trace_order
    ON kb_retrieval_action(trace_id, step_no, id);

CREATE TABLE IF NOT EXISTS kb_retrieval_evidence (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    action_id BIGINT REFERENCES kb_retrieval_action(id) ON DELETE SET NULL,
    dataset_id BIGINT NOT NULL,
    document_id BIGINT,
    document_version_id BIGINT,
    node_id BIGINT,
    retrieval_unit_id BIGINT,
    channel VARCHAR(20) NOT NULL,
    score DOUBLE PRECISION,
    rank INT,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    excerpt VARCHAR(2000) NOT NULL DEFAULT '',
    locator JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_retrieval_evidence_excerpt_bounded CHECK (char_length(excerpt) <= 2000),
    CONSTRAINT ck_retrieval_evidence_locator_bounded CHECK (octet_length(locator::text) <= 8192)
);

CREATE INDEX IF NOT EXISTS idx_retrieval_evidence_trace_order
    ON kb_retrieval_evidence(trace_id, id);
CREATE INDEX IF NOT EXISTS idx_retrieval_evidence_document
    ON kb_retrieval_evidence(dataset_id, document_id, document_version_id, node_id);
