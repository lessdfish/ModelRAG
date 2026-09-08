CREATE TABLE kb_document_node (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    document_version_id BIGINT NOT NULL REFERENCES kb_document_version(id),
    parent_id BIGINT REFERENCES kb_document_node(id),
    node_type VARCHAR(30) NOT NULL,
    depth INT NOT NULL,
    ordinal INT NOT NULL,
    title VARCHAR(1000),
    content TEXT,
    content_hash VARCHAR(64),
    page_from INT,
    page_to INT,
    char_start BIGINT,
    char_end BIGINT,
    token_count INT NOT NULL DEFAULT 0,
    searchable BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_document_node_depth_nonnegative CHECK (depth >= 0),
    CONSTRAINT ck_document_node_ordinal_nonnegative CHECK (ordinal >= 0),
    CONSTRAINT ck_document_node_token_count_nonnegative CHECK (token_count >= 0),
    CONSTRAINT ck_document_node_page_range CHECK (
        (page_from IS NULL AND page_to IS NULL)
        OR (page_from IS NOT NULL AND page_to IS NOT NULL AND page_from > 0 AND page_to >= page_from)
    ),
    CONSTRAINT ck_document_node_char_range CHECK (
        (char_start IS NULL AND char_end IS NULL)
        OR (char_start IS NOT NULL AND char_end IS NOT NULL AND char_start >= 0 AND char_end >= char_start)
    )
);

CREATE UNIQUE INDEX uq_document_node_root_version
    ON kb_document_node(document_version_id)
    WHERE parent_id IS NULL;
CREATE UNIQUE INDEX uq_document_node_sibling_ordinal
    ON kb_document_node(document_version_id, parent_id, ordinal)
    WHERE parent_id IS NOT NULL;

CREATE INDEX idx_document_node_version_parent_ordinal
    ON kb_document_node(document_version_id, parent_id, ordinal);
CREATE INDEX idx_document_node_version_type
    ON kb_document_node(document_version_id, node_type);
CREATE INDEX idx_document_node_document_version
    ON kb_document_node(document_id, document_version_id);
CREATE INDEX idx_document_node_dataset_version
    ON kb_document_node(dataset_id, document_version_id);
CREATE INDEX idx_document_node_parent_ordinal
    ON kb_document_node(parent_id, ordinal);
