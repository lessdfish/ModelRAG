CREATE TABLE kb_index_build (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    document_version_id BIGINT NOT NULL REFERENCES kb_document_version(id),
    build_no BIGINT NOT NULL,
    state VARCHAR(40) NOT NULL,
    embedding_profile VARCHAR(120) NOT NULL,
    rerank_profile VARCHAR(120),
    node_count BIGINT NOT NULL DEFAULT 0,
    unit_count BIGINT NOT NULL DEFAULT 0,
    vector_count BIGINT NOT NULL DEFAULT 0,
    lexical_count BIGINT NOT NULL DEFAULT 0,
    error_msg TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    start_time TIMESTAMPTZ,
    ready_time TIMESTAMPTZ,
    active_time TIMESTAMPTZ,
    failed_time TIMESTAMPTZ,
    CONSTRAINT uq_index_build_document_no UNIQUE (document_id, build_no),
    CONSTRAINT ck_index_build_no_positive CHECK (build_no > 0),
    CONSTRAINT ck_index_build_node_count_nonnegative CHECK (node_count >= 0),
    CONSTRAINT ck_index_build_unit_count_nonnegative CHECK (unit_count >= 0),
    CONSTRAINT ck_index_build_vector_count_nonnegative CHECK (vector_count >= 0),
    CONSTRAINT ck_index_build_lexical_count_nonnegative CHECK (lexical_count >= 0),
    CONSTRAINT ck_index_build_state CHECK (state IN (
        'CREATED', 'PARSING', 'STRUCTURE_READY', 'UNIT_BUILDING', 'UNIT_READY',
        'VECTOR_BUILDING', 'VECTOR_READY', 'LEXICAL_SYNCING', 'VERIFYING',
        'READY', 'ACTIVE', 'SUPERSEDED', 'FAILED'
    ))
);

CREATE INDEX idx_index_build_document_no_desc
    ON kb_index_build(document_id, build_no DESC);
CREATE INDEX idx_index_build_version_state
    ON kb_index_build(document_version_id, state);
CREATE INDEX idx_index_build_dataset_state
    ON kb_index_build(dataset_id, state);
CREATE INDEX idx_index_build_document_state
    ON kb_index_build(document_id, state);

ALTER TABLE kb_document
    ADD COLUMN IF NOT EXISTS active_index_build_id BIGINT;

CREATE INDEX idx_document_active_index_build
    ON kb_document(active_index_build_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_document_active_index_build') THEN
        ALTER TABLE kb_document
            ADD CONSTRAINT fk_document_active_index_build
            FOREIGN KEY (active_index_build_id) REFERENCES kb_index_build(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_retrieval_unit_index_build') THEN
        ALTER TABLE kb_retrieval_unit
            ADD CONSTRAINT fk_retrieval_unit_index_build
            FOREIGN KEY (index_build_id) REFERENCES kb_index_build(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_vector_embedding_index_build') THEN
        ALTER TABLE kb_vector_embedding
            ADD CONSTRAINT fk_vector_embedding_index_build
            FOREIGN KEY (index_build_id) REFERENCES kb_index_build(id);
    END IF;
END $$;
