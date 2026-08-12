ALTER TABLE kb_model_health ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE kb_model_health ADD COLUMN IF NOT EXISTS canary_percent INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_model_health_candidate
    ON kb_model_health(model_type, enabled, priority DESC, model_name);
