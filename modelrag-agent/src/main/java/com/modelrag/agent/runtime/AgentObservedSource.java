package com.modelrag.agent.runtime;

/** Version-aware source capability persisted with an agent checkpoint. */
public record AgentObservedSource(long nodeId, long documentId, long documentVersionId,
        Long indexBuildId, String documentName, String titlePath, String nodeType, double score) {
    public AgentObservedSource {
        if (nodeId <= 0 || documentId <= 0 || documentVersionId <= 0) {
            throw new IllegalArgumentException("observed source identity is invalid");
        }
        if (indexBuildId != null && indexBuildId <= 0) {
            throw new IllegalArgumentException("indexBuildId must be positive");
        }
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            throw new IllegalArgumentException("observed source score is invalid");
        }
        documentName = bounded(documentName, 500);
        titlePath = bounded(titlePath, 1_000);
        nodeType = bounded(nodeType, 80);
    }

    private static String bounded(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
