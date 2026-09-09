package com.modelrag.search.channel.v2;

import java.util.LinkedHashSet;
import java.util.List;

/** Bounded V2 lexical request scoped to one observed document and active build. */
public record DocumentLexicalSearchRequest(long datasetId, long documentId, String query,
        List<Long> activeIndexBuildIds, int limit) {
    public static final int MAX_LIMIT = 100;
    public static final int MAX_ACTIVE_BUILD_IDS = LexicalSearchRequest.MAX_ACTIVE_BUILD_IDS;

    public DocumentLexicalSearchRequest {
        if (datasetId <= 0 || documentId <= 0) {
            throw new IllegalArgumentException("datasetId and documentId must be positive");
        }
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit is out of bounds");
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        if (activeIndexBuildIds != null) {
            for (Long id : activeIndexBuildIds) {
                if (id == null || id <= 0) throw new IllegalArgumentException("active build ID is invalid");
                unique.add(id);
            }
        }
        if (unique.size() > MAX_ACTIVE_BUILD_IDS) {
            throw new IllegalArgumentException("active build scope is out of bounds");
        }
        activeIndexBuildIds = List.copyOf(unique);
    }

    public List<Long> activeBuildIds() {
        return activeIndexBuildIds;
    }
}
