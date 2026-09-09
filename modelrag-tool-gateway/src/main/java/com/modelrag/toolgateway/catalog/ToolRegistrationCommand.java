package com.modelrag.toolgateway.catalog;

import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashSet;

/** Write-only tool registration input. The secret is never returned as a descriptor. */
public record ToolRegistrationCommand(String name, String description, String riskLevel, boolean enabled,
        String type, String endpoint, String authHeaderName, String authHeaderValue, String jsonSchema,
        Set<String> allowedRoles, Set<Long> allowedDatasetIds, boolean idempotent) {
    public ToolRegistrationCommand {
        allowedRoles = allowedRoles == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedRoles));
        allowedDatasetIds = allowedDatasetIds == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedDatasetIds));
    }

    @Override
    public String toString() {
        return "ToolRegistrationCommand[name=" + name + ", riskLevel=" + riskLevel
                + ", type=" + type + ", hasAuthSecret="
                + (authHeaderValue != null && !authHeaderValue.isBlank()) + "]";
    }
}
