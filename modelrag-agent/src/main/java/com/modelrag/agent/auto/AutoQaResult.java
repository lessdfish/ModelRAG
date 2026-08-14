package com.modelrag.agent.auto;

import com.modelrag.agent.router.RouteDecision;
import com.modelrag.qa.dto.Citation;

import java.util.List;

public record AutoQaResult(
        long datasetId,
        String datasetName,
        RouteDecision route,
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
    public AutoQaResult(long datasetId, String datasetName, RouteDecision route, String status, String answer,
                        List<Citation> citations, double confidence, boolean refused, String traceId, String executionId,
                        String approvalId, List<String> steps) {
        this(datasetId, datasetName, route, status, answer, citations, confidence, refused, traceId, executionId,
                approvalId, steps, List.of());
    }

    public AutoQaResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        steps = steps == null ? List.of() : List.copyOf(steps);
        degradedComponents = degradedComponents == null ? List.of() : List.copyOf(degradedComponents);
    }
}
