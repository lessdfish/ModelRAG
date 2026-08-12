package com.modelrag.common.security;

import java.util.Set;

public record RequestUser(String id, Set<String> roles, Set<Long> datasetIds) {
    public boolean hasRole(String role) {
        return roles.contains(role) || roles.contains("ADMIN");
    }

    public boolean canAccess(long datasetId) {
        return roles.contains("ADMIN") || datasetIds.contains(datasetId);
    }
}
