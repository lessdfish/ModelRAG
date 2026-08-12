ALTER TABLE kb_tool_definition ADD COLUMN IF NOT EXISTS allowed_roles JSONB NOT NULL DEFAULT '[]';
ALTER TABLE kb_tool_definition ADD COLUMN IF NOT EXISTS allowed_dataset_ids JSONB NOT NULL DEFAULT '[]';
