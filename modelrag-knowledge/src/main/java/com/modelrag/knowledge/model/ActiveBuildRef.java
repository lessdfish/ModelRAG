package com.modelrag.knowledge.model;

/** PostgreSQL-derived active V2 build scope entry used by lexical retrieval. */
public record ActiveBuildRef(long documentId, long documentVersionId, long indexBuildId,
        String embeddingProfile) {
    public ActiveBuildRef {
        if (documentId <= 0 || documentVersionId <= 0 || indexBuildId <= 0) {
            throw new IllegalArgumentException("active build identity is invalid");
        }
        if (embeddingProfile == null || embeddingProfile.isBlank()) {
            throw new IllegalArgumentException("active build embedding profile is blank");
        }
    }
}
