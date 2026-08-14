package com.modelrag.common.security;

import java.util.Map;
import java.util.Set;

public record RequestUser(String id, Set<String> roles, Set<Long> datasetIds, Map<Long, String> datasetPermissions) {
    public RequestUser(String id, Set<String> roles, Set<Long> datasetIds) {
        this(id, roles, datasetIds, datasetIds == null ? Map.of() : datasetIds.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(value -> value, ignored -> "READ")));
    }

    public RequestUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        datasetIds = datasetIds == null ? Set.of() : Set.copyOf(datasetIds);
        datasetPermissions = datasetPermissions == null ? Map.of() : Map.copyOf(datasetPermissions);
    }

    public boolean hasRole(String role) {
        return roles.contains(role) || roles.contains("ADMIN");
    }

    public boolean canAccess(long datasetId) {
        return roles.contains("ADMIN") || datasetIds.contains(datasetId);
    }

    public boolean canWrite(long datasetId) {
        if (roles.contains("ADMIN")) return true;
        String permission = datasetPermissions.get(datasetId);
        return "WRITE".equalsIgnoreCase(permission) || "ADMIN".equalsIgnoreCase(permission);
    }

    public boolean canAdminister(long datasetId) {
        return roles.contains("ADMIN") || "ADMIN".equalsIgnoreCase(datasetPermissions.get(datasetId));
    }
}
