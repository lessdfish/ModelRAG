package com.modelrag.qa.evidence;

import java.util.LinkedHashSet;
import java.util.List;

/** Bounded evidence contract shared by V2 answer generation and citations. */
public record EvidenceSet(String traceId, String query, List<Evidence> evidence,
        EvidenceSufficiency sufficiency, List<String> degradedComponents,
        long retrievalLatencyMs, int navigationActions) {
    public static final int MAX_EVIDENCE = 64;

    public EvidenceSet {
        if (traceId == null || traceId.isBlank() || query == null || query.isBlank()
                || sufficiency == null || retrievalLatencyMs < 0 || navigationActions < 0) {
            throw new IllegalArgumentException("evidence set fields are invalid");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (evidence.size() > MAX_EVIDENCE) throw new IllegalArgumentException("evidence set is too large");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Evidence item : evidence) {
            if (item == null || !ids.add(item.evidenceId())) {
                throw new IllegalArgumentException("evidence IDs must be unique");
            }
        }
        degradedComponents = degradedComponents == null ? List.of()
                : degradedComponents.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public List<Evidence> primaryEvidence() {
        return evidence.stream().filter(Evidence::primary).toList();
    }

    public List<Evidence> expandedEvidence() {
        return evidence.stream().filter(item -> !item.primary()).toList();
    }
}
