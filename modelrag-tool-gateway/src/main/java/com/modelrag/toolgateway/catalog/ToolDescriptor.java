package com.modelrag.toolgateway.catalog;

import java.util.Set;

/** Secret-free tool metadata safe for Agent planning and API responses. */
public record ToolDescriptor(String name, String description, String riskLevel, boolean enabled,
        String type, String endpoint, String authHeaderName, String jsonSchema,
        Set<String> allowedRoles, Set<Long> allowedDatasetIds, boolean idempotent,
        boolean hasAuthSecret) {
    public ToolDescriptor(String name, String description, String riskLevel, boolean enabled) {
        this(name, description, riskLevel, enabled, "INTERNAL", null, null, "{}", Set.of(), Set.of(),
                "LOW".equalsIgnoreCase(riskLevel), false);
    }

    public ToolDescriptor(String name, String description, String riskLevel, boolean enabled,
            String type, String endpoint, String authHeaderName, String jsonSchema,
            Set<String> allowedRoles, Set<Long> allowedDatasetIds, boolean idempotent) {
        this(name, description, riskLevel, enabled, type, endpoint, authHeaderName, jsonSchema,
                allowedRoles, allowedDatasetIds, idempotent, false);
    }

    public ToolDescriptor {
        allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
        allowedDatasetIds = allowedDatasetIds == null ? Set.of() : Set.copyOf(allowedDatasetIds);
    }

    public boolean http() {
        return "HTTP".equalsIgnoreCase(type) && endpoint != null && !endpoint.isBlank();
    }

    public String risk() { return riskLevel; }
}
