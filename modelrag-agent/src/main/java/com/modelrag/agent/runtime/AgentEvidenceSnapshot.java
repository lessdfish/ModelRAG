package com.modelrag.agent.runtime;

/** Bounded, metadata-free evidence representation used in recovery state. */
public record AgentEvidenceSnapshot(String evidenceId, long datasetId, long documentId,
        long documentVersionId, long nodeId, Long retrievalUnitId, Long indexBuildId,
        String documentName, String origin, String nodeType, String unitType, String titlePath,
        String boundedContentOrExcerpt, String locator, double score, String channel, boolean primary) {
    public static final int MAX_EXCERPT_CHARS = 1_000;

    public AgentEvidenceSnapshot {
        if (evidenceId == null || evidenceId.isBlank() || datasetId <= 0 || documentId <= 0
                || documentVersionId <= 0 || nodeId <= 0) {
            throw new IllegalArgumentException("evidence snapshot identity is invalid");
        }
        if (retrievalUnitId != null && retrievalUnitId <= 0) {
            throw new IllegalArgumentException("retrievalUnitId must be positive");
        }
        if (indexBuildId != null && indexBuildId <= 0) {
            throw new IllegalArgumentException("indexBuildId must be positive");
        }
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            throw new IllegalArgumentException("evidence snapshot score is invalid");
        }
        documentName = bounded(documentName, 500);
        origin = bounded(origin, 80);
        nodeType = bounded(nodeType, 80);
        unitType = bounded(unitType, 80);
        titlePath = bounded(titlePath, 1_000);
        boundedContentOrExcerpt = bounded(boundedContentOrExcerpt, MAX_EXCERPT_CHARS);
        locator = bounded(locator, 500);
        channel = bounded(channel, 80);
    }

    private static String bounded(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
