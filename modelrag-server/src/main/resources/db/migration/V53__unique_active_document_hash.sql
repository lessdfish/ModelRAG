WITH duplicates AS (
    SELECT id
    FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY dataset_id, file_hash ORDER BY id) AS position
        FROM kb_document
        WHERE delete_time IS NULL AND file_hash IS NOT NULL
    ) ranked
    WHERE position > 1
)
UPDATE kb_chunk SET delete_time=NOW()
WHERE document_id IN (SELECT id FROM duplicates) AND delete_time IS NULL;

WITH duplicates AS (
    SELECT id
    FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY dataset_id, file_hash ORDER BY id) AS position
        FROM kb_document
        WHERE delete_time IS NULL AND file_hash IS NOT NULL
    ) ranked
    WHERE position > 1
)
UPDATE kb_document
SET delete_time=NOW(), index_state='DELETED', error_msg='duplicate file hash retired by V53', update_time=NOW()
WHERE id IN (SELECT id FROM duplicates);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_active_hash
    ON kb_document(dataset_id, file_hash)
    WHERE delete_time IS NULL AND file_hash IS NOT NULL;
