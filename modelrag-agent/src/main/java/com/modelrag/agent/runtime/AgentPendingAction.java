package com.modelrag.agent.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Write-ahead action descriptor. It intentionally contains no tool secrets. */
public record AgentPendingAction(String actionId, AgentPendingActionKind kind, String actionName,
        Map<String, Object> arguments, String idempotencyKey, boolean requiresApproval,
        boolean toolIdempotent, Instant createdAt) {
    public AgentPendingAction {
        if (actionId == null || actionId.isBlank() || kind == null || actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("pending action identity is invalid");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
