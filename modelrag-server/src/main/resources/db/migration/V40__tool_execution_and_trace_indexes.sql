CREATE INDEX IF NOT EXISTS idx_tool_definition_enabled ON kb_tool_definition(enabled, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tool_trace_trace_id ON kb_tool_trace(trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tool_trace_time ON kb_tool_trace(create_time DESC);
