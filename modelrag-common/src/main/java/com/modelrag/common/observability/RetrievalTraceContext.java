package com.modelrag.common.observability;

import java.util.UUID;

/** Correlation identifiers carried by one retrieval or agent execution. */
public record RetrievalTraceContext(
        String requestId,
        String traceId,
        String executionId,
        String mode,
        long datasetId,
        String userId,
        String profile) {

    public RetrievalTraceContext {
        requestId = required(requestId, "requestId", 128);
        traceId = required(traceId, "traceId", 64);
        executionId = optional(executionId, 80);
        mode = required(mode, "mode", 32);
        userId = optional(userId, 128);
        profile = optional(profile, 100);
    }

    public static RetrievalTraceContext create(String mode, long datasetId, String userId, String profile) {
        String requestId = TraceCorrelation.currentRequestId();
        return new RetrievalTraceContext(requestId, UUID.randomUUID().toString(), null, mode, datasetId, userId,
                profile);
    }

    public RetrievalTraceContext withExecutionId(String value) {
        return new RetrievalTraceContext(requestId, traceId, value, mode, datasetId, userId, profile);
    }

    private static String required(String value, String field, int max) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        return result.length() <= max ? result : result.substring(0, max);
    }
}
