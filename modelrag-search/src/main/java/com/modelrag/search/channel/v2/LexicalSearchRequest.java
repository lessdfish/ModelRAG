package com.modelrag.search.channel.v2;

import java.util.LinkedHashSet;
import java.util.List;

/** Bounded V2 lexical query carrying the PostgreSQL-resolved build filter. */
public record LexicalSearchRequest(long datasetId, String query, List<Long> activeIndexBuildIds, int limit) {
    public static final int MAX_LIMIT = 500;
    public static final int MAX_ACTIVE_BUILD_IDS = 10_000;

    public LexicalSearchRequest(long datasetId, String query, int limit, List<Long> activeIndexBuildIds) {
        this(datasetId, query, activeIndexBuildIds, limit);
    }

    public LexicalSearchRequest {
        if (datasetId <= 0) throw new IllegalArgumentException("datasetId must be positive");
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
