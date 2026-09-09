package com.modelrag.agent.orchestrator;

import com.modelrag.qa.dto.Citation;
import java.util.List;

/** Result of one execution-local read-only retrieval loop. */
public record AgenticRetrievalResult(String executionId, String status, String answer,
        List<Citation> citations, double confidence, boolean refused, String traceId,
        List<String> steps, List<String> degradedComponents) {
    public AgenticRetrievalResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        citations = citations == null ? List.of() : List.copyOf(citations);
        degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public AgenticRetrievalResult(String executionId, String status, String answer,
            List<Citation> citations, double confidence, boolean refused, String traceId,
            List<String> steps) {
        this(executionId, status, answer, citations, confidence, refused, traceId, steps, List.of());
    }
}
