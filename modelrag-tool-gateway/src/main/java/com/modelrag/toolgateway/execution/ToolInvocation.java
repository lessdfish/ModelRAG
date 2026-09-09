package com.modelrag.toolgateway.execution;

import java.util.Set;

/** Secret-free command submitted to the in-process Tool Gateway. */
public record ToolInvocation(String executionId, String actionId, String toolName,
        String userId, Set<String> userRoles, long datasetId, Long conversationId,
        String input, String idempotencyKey, String traceId) {
    public ToolInvocation {
        userRoles = userRoles == null ? Set.of() : Set.copyOf(userRoles);
        input = input == null ? "" : input;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        traceId = traceId == null ? "" : traceId;
    }
}
