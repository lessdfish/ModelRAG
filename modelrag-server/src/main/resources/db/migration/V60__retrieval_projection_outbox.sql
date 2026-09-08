CREATE TABLE kb_retrieval_projection_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    document_version_id BIGINT NOT NULL REFERENCES kb_document_version(id),
    index_build_id BIGINT NOT NULL REFERENCES kb_index_build(id),
    retrieval_unit_id BIGINT NOT NULL REFERENCES kb_retrieval_unit(id),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    dead_letter BOOLEAN NOT NULL DEFAULT FALSE,
    error_msg TEXT,
    idempotency_key VARCHAR(300) NOT NULL UNIQUE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_retrieval_projection_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CONSTRAINT ck_retrieval_projection_outbox_retry_nonnegative CHECK (retry_count >= 0)
);

CREATE INDEX idx_retrieval_projection_outbox_due
    ON kb_retrieval_projection_outbox(status, next_retry_at, id);
CREATE INDEX idx_retrieval_projection_outbox_build_status
    ON kb_retrieval_projection_outbox(index_build_id, status);
CREATE INDEX idx_retrieval_projection_outbox_document_build
    ON kb_retrieval_projection_outbox(document_id, index_build_id);
CREATE INDEX idx_retrieval_projection_outbox_unit
    ON kb_retrieval_projection_outbox(retrieval_unit_id);
