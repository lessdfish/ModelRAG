package com.modelrag.agent.runtime;

/** Bounded citation identity retained in a terminal result snapshot. */
public record AgentCitationSnapshot(long documentId, long documentVersionId, long nodeId,
        String excerpt, double score) {
    public AgentCitationSnapshot {
        if (documentId <= 0 || documentVersionId <= 0 || nodeId <= 0) {
            throw new IllegalArgumentException("citation snapshot identity is invalid");
        }
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            throw new IllegalArgumentException("citation score is invalid");
        }
        excerpt = excerpt == null ? "" : excerpt.length() <= 1_000 ? excerpt : excerpt.substring(0, 1_000);
    }
}
