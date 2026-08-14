package com.modelrag.qa.dto;
import java.util.List;
public record QaResult(String answer, List<Citation> citations, double confidence, boolean refused, String traceId,
        List<String> degradedComponents) {
    public QaResult(String answer, List<Citation> citations, double confidence, boolean refused, String traceId) {
        this(answer, citations, confidence, refused, traceId, List.of());
    }

    public QaResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        degradedComponents = degradedComponents == null ? List.of() : List.copyOf(degradedComponents);
    }
}
