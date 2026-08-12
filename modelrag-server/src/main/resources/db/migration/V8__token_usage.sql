CREATE TABLE IF NOT EXISTS kb_token_usage_daily (
    usage_date DATE NOT NULL,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    embedding_tokens BIGINT NOT NULL DEFAULT 0,
    rerank_calls BIGINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (usage_date,dataset_id)
);
