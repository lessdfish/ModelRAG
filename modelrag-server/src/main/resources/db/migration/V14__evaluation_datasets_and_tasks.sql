CREATE TABLE IF NOT EXISTS kb_eval_dataset (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    question TEXT NOT NULL,
    expected_chunks JSONB NOT NULL DEFAULT '[]',
    expected_answer TEXT,
    should_refuse BOOLEAN NOT NULL DEFAULT FALSE,
    category VARCHAR(80),
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_dataset ON kb_eval_dataset(dataset_id,id);
CREATE TABLE IF NOT EXISTS kb_eval_task (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    status VARCHAR(20) NOT NULL,
    report JSONB NOT NULL DEFAULT '{}',
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_eval_task_dataset ON kb_eval_task(dataset_id,id DESC);
