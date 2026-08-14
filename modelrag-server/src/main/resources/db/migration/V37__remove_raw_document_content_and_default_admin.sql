ALTER TABLE kb_document DROP COLUMN IF EXISTS source_content;
ALTER TABLE kb_dataset ALTER COLUMN embedding_model SET DEFAULT 'Qwen3-Embedding-0.6B';
UPDATE kb_dataset SET embedding_model='Qwen3-Embedding-0.6B', embedding_dimensions=1024,
    embedding_profile_version='qwen3-embedding-0.6b-1024-v1'
    WHERE embedding_model IS NULL OR embedding_model ILIKE '%bge%';
DELETE FROM kb_user_role WHERE user_id='admin';
DELETE FROM kb_user_account WHERE user_id='admin';
