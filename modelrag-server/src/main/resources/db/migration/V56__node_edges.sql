CREATE TABLE kb_node_edge (
    id BIGSERIAL PRIMARY KEY,
    from_node_id BIGINT NOT NULL REFERENCES kb_document_node(id),
    to_node_id BIGINT NOT NULL REFERENCES kb_document_node(id),
    edge_type VARCHAR(30) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_node_edge_type CHECK (edge_type IN ('REFERENCE', 'ATTACHMENT', 'RELATED', 'SUPERSEDES', 'MENTIONS'))
);

CREATE UNIQUE INDEX uq_node_edge_from_to_type
    ON kb_node_edge(from_node_id, to_node_id, edge_type);
CREATE INDEX idx_node_edge_from_type
    ON kb_node_edge(from_node_id, edge_type);
CREATE INDEX idx_node_edge_to_type
    ON kb_node_edge(to_node_id, edge_type);
