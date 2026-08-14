CREATE TABLE IF NOT EXISTS kb_api_idempotency (
    caller_scope VARCHAR(64) NOT NULL,
    http_method VARCHAR(8) NOT NULL,
    request_path VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (caller_scope, http_method, request_path, idempotency_key),
    CONSTRAINT ck_api_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED'))
);

CREATE INDEX IF NOT EXISTS idx_api_idempotency_expiry ON kb_api_idempotency(created_at);
