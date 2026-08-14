CREATE TABLE IF NOT EXISTS kb_model_config (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES kb_user_account(user_id) ON DELETE CASCADE,
    model_type VARCHAR(40) NOT NULL,
    provider VARCHAR(120) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    base_url VARCHAR(500),
    secret_id VARCHAR(200),
    api_key_ciphertext TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    capability_status VARCHAR(30) NOT NULL DEFAULT 'UNPROBED',
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    revoked_at TIMESTAMPTZ,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, model_type, provider, model_name)
);
CREATE INDEX IF NOT EXISTS idx_model_config_user ON kb_model_config(user_id, update_time DESC);
