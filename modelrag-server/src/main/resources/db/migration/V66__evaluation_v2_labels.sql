-- G11 canonical evaluation labels. expected_chunks remains for V1 compatibility.
ALTER TABLE kb_eval_dataset
    ADD COLUMN IF NOT EXISTS expected_document_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS expected_node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS expected_evidence_groups JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_eval_dataset_category
    ON kb_eval_dataset(dataset_id, category);
