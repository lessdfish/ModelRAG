package com.modelrag.api;

public interface ToolInvoker {
    ToolResult invoke(ToolDefinition definition, String userId, long datasetId, String idempotencyKey,
            String parameters);

    record ToolResult(String status, String summary, String traceId, boolean approvalRequired) { }
}
