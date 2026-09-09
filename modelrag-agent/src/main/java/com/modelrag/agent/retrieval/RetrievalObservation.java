package com.modelrag.agent.retrieval;

import com.modelrag.qa.evidence.Evidence;
import java.util.List;

/** Structured, bounded result of a read-only retrieval action. */
public record RetrievalObservation(RetrievalActionName action, String summary,
        List<RetrievalObservationItem> items, List<Evidence> newEvidence,
        List<String> degradedComponents, long latencyMs) {
    public static final int MAX_ITEMS = 20;

    public RetrievalObservation {
        if (action == null || action == RetrievalActionName.FINISH) {
            throw new IllegalArgumentException("observation action is invalid");
        }
        if (latencyMs < 0) throw new IllegalArgumentException("latencyMs must not be negative");
        summary = limit(summary, 500);
        items = items == null ? List.of() : List.copyOf(items);
        if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("observation item budget exceeded");
        newEvidence = newEvidence == null ? List.of() : newEvidence.stream()
                .filter(value -> value != null).limit(64).toList();
        degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private static String limit(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
