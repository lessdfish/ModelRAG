CREATE TABLE kb_retrieval_unit (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    document_version_id BIGINT NOT NULL REFERENCES kb_document_version(id),
    node_id BIGINT NOT NULL REFERENCES kb_document_node(id),
    index_build_id BIGINT NOT NULL,
    unit_type VARCHAR(30) NOT NULL,
    ordinal INT NOT NULL,
    title_path TEXT,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    token_count INT NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_retrieval_unit_ordinal_nonnegative CHECK (ordinal >= 0),
    CONSTRAINT ck_retrieval_unit_token_count_nonnegative CHECK (token_count >= 0),
    CONSTRAINT ck_retrieval_unit_content_nonblank CHECK (btrim(content) <> ''),
    CONSTRAINT ck_retrieval_unit_content_hash_nonblank CHECK (btrim(content_hash) <> ''),
    CONSTRAINT ck_retrieval_unit_type CHECK (unit_type IN (
        'PARAGRAPH', 'WINDOW', 'SECTION', 'SECTION_SUMMARY', 'TABLE', 'TITLE_PATH'
    ))
);

CREATE UNIQUE INDEX uq_retrieval_unit_build_node_type_ordinal
    ON kb_retrieval_unit(index_build_id, node_id, unit_type, ordinal);
CREATE INDEX idx_retrieval_unit_build_id
    ON kb_retrieval_unit(index_build_id, id);
CREATE INDEX idx_retrieval_unit_build_node
    ON kb_retrieval_unit(index_build_id, node_id);
CREATE INDEX idx_retrieval_unit_version_node
    ON kb_retrieval_unit(document_version_id, node_id);
CREATE INDEX idx_retrieval_unit_dataset_document_build
    ON kb_retrieval_unit(dataset_id, document_id, index_build_id);
CREATE INDEX idx_retrieval_unit_node_type
    ON kb_retrieval_unit(node_id, unit_type);
