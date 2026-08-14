ALTER TABLE kb_tool_definition
    ADD COLUMN IF NOT EXISTS idempotent BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE kb_tool_definition
SET idempotent = TRUE
WHERE name = 'knowledge_lookup';
