CREATE TABLE IF NOT EXISTS kb_intent_node (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    parent_id BIGINT REFERENCES kb_intent_node(id),
    name VARCHAR(100) NOT NULL,
    node_type VARCHAR(20) NOT NULL DEFAULT 'TOPIC',
    target_type VARCHAR(20) NOT NULL DEFAULT 'RAG',
    target_id VARCHAR(100),
    description TEXT,
    priority INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_intent_node_dataset ON kb_intent_node(dataset_id,enabled,priority DESC);
