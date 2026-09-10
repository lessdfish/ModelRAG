package com.modelrag.server.eval;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/** Canonical evidence-group label; empty dimensions are intentionally not fabricated. */
public record EvalEvidenceGroup(@JsonAlias("name") String id, List<Long> documentIds, List<Long> nodeIds,
        List<Long> retrievalUnitIds) {
    public EvalEvidenceGroup {
        id = id == null || id.isBlank() ? "group" : id.trim();
        documentIds = clean(documentIds);
        nodeIds = clean(nodeIds);
        retrievalUnitIds = clean(retrievalUnitIds);
    }

    private static List<Long> clean(List<Long> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && value > 0).distinct().toList();
    }
}
