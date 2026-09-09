package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.model.NodeType;
import com.modelrag.qa.evidence.EvidenceOrigin;

/** Bounded source metadata exposed to the policy and the caller. */
public record RetrievalObservationItem(Long retrievalUnitId, long nodeId, long documentId,
        long documentVersionId, Long indexBuildId, String titlePath, NodeType nodeType,
        EvidenceOrigin origin, double score, String excerpt, Integer pageFrom, Integer pageTo) {
    public static final int MAX_EXCERPT_CHARS = 500;
    public static final int MAX_TITLE_PATH_CHARS = 1000;

    public RetrievalObservationItem {
        if (nodeId <= 0 || documentId <= 0 || documentVersionId <= 0) {
            throw new IllegalArgumentException("observation source identity is invalid");
        }
        if (retrievalUnitId != null && retrievalUnitId <= 0) {
            throw new IllegalArgumentException("retrievalUnitId must be positive");
        }
        if (indexBuildId != null && indexBuildId <= 0) {
            throw new IllegalArgumentException("indexBuildId must be positive");
        }
        if (nodeType == null || origin == null || Double.isNaN(score) || Double.isInfinite(score)) {
            throw new IllegalArgumentException("observation source fields are invalid");
        }
        titlePath = limit(titlePath, MAX_TITLE_PATH_CHARS);
        excerpt = limit(excerpt, MAX_EXCERPT_CHARS);
        if ((pageFrom == null) != (pageTo == null)
                || (pageFrom != null && (pageFrom <= 0 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("observation page range is invalid");
        }
    }

    private static String limit(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
