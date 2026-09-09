package com.modelrag.agent.runtime;

import java.util.List;

/** Bounded terminal result persisted for idempotent client retries. */
public record AgentResultSnapshot(String status, String answer, List<AgentCitationSnapshot> citations,
        double confidence, boolean refused, String traceId, List<String> degradedComponents) {
    public AgentResultSnapshot {
        status = status == null ? "" : status;
        answer = answer == null ? "" : answer.length() <= 4_000 ? answer : answer.substring(0, 4_000);
        if (Double.isNaN(confidence) || Double.isInfinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("result confidence is invalid");
        }
        citations = citations == null ? List.of() : citations.stream().filter(value -> value != null).limit(16).toList();
        traceId = traceId == null ? "" : traceId;
        degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().limit(16).toList();
    }
}
