package com.modelrag.api;

import java.util.List;

public record AssistantAnswerResponse(
        String route,
        String status,
        String answer,
        List<Citation> citations,
        double confidence,
        boolean refused,
        String traceId,
        String executionId,
        String approvalId,
        List<String> steps,
        List<String> degradedComponents) {
    public record Citation(long chunkId, long documentId, String documentName, String location,
            long indexVersion, String excerpt, double score) { }
}
