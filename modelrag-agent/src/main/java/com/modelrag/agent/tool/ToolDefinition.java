package com.modelrag.agent.tool;

import java.util.Set;

public record ToolDefinition(String name, String description, String riskLevel, boolean enabled,
        String type, String endpoint, String authHeaderName, String authHeaderValue, String jsonSchema,
        Set<String> allowedRoles, Set<Long> allowedDatasetIds) {
    public ToolDefinition(String name, String description, String riskLevel, boolean enabled) {
        this(name, description, riskLevel, enabled, "INTERNAL", null, null, null, null, Set.of(), Set.of());
    }

    public ToolDefinition(String name, String description, String riskLevel, boolean enabled,
            String type, String endpoint, String authHeaderName, String authHeaderValue) {
        this(name, description, riskLevel, enabled, type, endpoint, authHeaderName, authHeaderValue, null, Set.of(), Set.of());
    }

    public ToolDefinition(String name, String description, String riskLevel, boolean enabled,
            String type, String endpoint, String authHeaderName, String authHeaderValue, String jsonSchema) {
        this(name, description, riskLevel, enabled, type, endpoint, authHeaderName, authHeaderValue, jsonSchema, Set.of(), Set.of());
    }

    public boolean http() {
        return "HTTP".equalsIgnoreCase(type) && endpoint != null && !endpoint.isBlank();
    }
}
