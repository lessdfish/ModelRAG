package com.modelrag.agent.runtime;

import java.util.List;

/** Safe summary of one bounded action observation. */
public record AgentObservationSnapshot(String action, String summary, int itemCount,
        int newEvidenceCount, List<String> degradedComponents, long latencyMs) {
    public AgentObservationSnapshot {
        if (action == null || action.isBlank() || itemCount < 0 || newEvidenceCount < 0 || latencyMs < 0) {
            throw new IllegalArgumentException("observation snapshot is invalid");
        }
        action = bounded(action, 80);
        summary = bounded(summary, 500);
        degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream()
                .filter(value -> value != null && !value.isBlank()).map(value -> bounded(value, 120))
                .distinct().limit(16).toList();
    }

    private static String bounded(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
