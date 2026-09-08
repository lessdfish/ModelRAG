CREATE TABLE kb_vector_embedding (
    id BIGSERIAL PRIMARY KEY,
    retrieval_unit_id BIGINT NOT NULL REFERENCES kb_retrieval_unit(id),
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id),
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    document_version_id BIGINT NOT NULL REFERENCES kb_document_version(id),
    index_build_id BIGINT NOT NULL,
    embedding_profile VARCHAR(120) NOT NULL,
    embedding vector(1024) NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_vector_embedding_unit_profile
    ON kb_vector_embedding(retrieval_unit_id, embedding_profile);
CREATE INDEX idx_vector_embedding_build
    ON kb_vector_embedding(index_build_id);
CREATE INDEX idx_vector_embedding_dataset_build
    ON kb_vector_embedding(dataset_id, index_build_id);
CREATE INDEX idx_vector_embedding_document_build
    ON kb_vector_embedding(document_id, index_build_id);
CREATE INDEX idx_vector_embedding_profile_build
    ON kb_vector_embedding(embedding_profile, index_build_id);
CREATE INDEX idx_vector_embedding_hnsw
    ON kb_vector_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);
