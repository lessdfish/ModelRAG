CREATE TABLE IF NOT EXISTS kb_ab_experiment (
    id VARCHAR(80) PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    traffic_percent INT NOT NULL DEFAULT 10,
    variant_top_k INT NOT NULL DEFAULT 8,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ab_experiment_dataset ON kb_ab_experiment(dataset_id, enabled);

CREATE TABLE IF NOT EXISTS kb_ab_event (
    id BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(80) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    dataset_id BIGINT NOT NULL,
    variant_name VARCHAR(120) NOT NULL,
    baseline_top_k INT,
    variant_top_k INT,
    baseline_final_count INT,
    variant_final_count INT,
    delta_count INT,
    refused BOOLEAN DEFAULT FALSE,
    confidence DECIMAL(5,4),
    latency_ms INT,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ab_event_dataset_time ON kb_ab_event(dataset_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_ab_event_experiment ON kb_ab_event(experiment_id, create_time DESC);
