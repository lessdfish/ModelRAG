CREATE TABLE kb_document_version (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    version_no BIGINT NOT NULL,
    source_hash VARCHAR(64),
    source_object_key VARCHAR(1000),
    artifact_object_key VARCHAR(1000),
    content_hash VARCHAR(64),
    parser_name VARCHAR(100),
    parser_version VARCHAR(80),
    parse_status VARCHAR(30) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ready_time TIMESTAMPTZ,
    CONSTRAINT ck_document_version_no_positive CHECK (version_no > 0)
);

CREATE UNIQUE INDEX uq_document_version_document_no
    ON kb_document_version(document_id, version_no);
CREATE INDEX idx_document_version_document_no_desc
    ON kb_document_version(document_id, version_no DESC);
CREATE INDEX idx_document_version_document_status
    ON kb_document_version(document_id, parse_status);

ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS active_version_id BIGINT;

INSERT INTO kb_document_version(
        document_id, version_no, source_hash, source_object_key, artifact_object_key,
        content_hash, parse_status, metadata, ready_time)
SELECT d.id, 1, d.file_hash, d.source_object_key, d.artifact_object_key,
       d.content_hash,
       CASE
           WHEN d.artifact_object_key IS NOT NULL THEN 'READY'
           WHEN d.index_status = 'FAILED' THEN 'FAILED'
           ELSE 'PENDING'
       END,
       '{"migration":"V54","source":"legacy-document"}'::jsonb,
       CASE WHEN d.artifact_object_key IS NOT NULL THEN NOW() ELSE NULL END
FROM kb_document d
WHERE NOT EXISTS (
    SELECT 1 FROM kb_document_version v
    WHERE v.document_id=d.id
);

UPDATE kb_document d
SET active_version_id=v.id
FROM kb_document_version v
WHERE v.document_id=d.id AND v.version_no=1 AND d.active_version_id IS NULL;

ALTER TABLE kb_document
    ADD CONSTRAINT fk_document_active_version
    FOREIGN KEY (active_version_id) REFERENCES kb_document_version(id);

CREATE INDEX idx_document_active_version ON kb_document(active_version_id);
