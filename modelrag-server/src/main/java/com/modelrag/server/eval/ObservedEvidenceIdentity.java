package com.modelrag.server.eval;

/** Canonical identity of one actual evidence item; dimensions never cross between items. */
public record ObservedEvidenceIdentity(long documentId, Long documentVersionId, Long nodeId,
        Long retrievalUnitId, int rank, boolean selected) {
    public ObservedEvidenceIdentity {
        if (documentId <= 0) throw new IllegalArgumentException("documentId must be positive");
        documentVersionId = positiveOrNull(documentVersionId);
        nodeId = positiveOrNull(nodeId);
        retrievalUnitId = positiveOrNull(retrievalUnitId);
        rank = Math.max(0, rank);
    }

    private static Long positiveOrNull(Long value) { return value != null && value > 0 ? value : null; }
}
