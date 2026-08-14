package com.modelrag.server.model;

import jakarta.validation.constraints.NotBlank;

public record ModelCandidateRequest(@NotBlank String modelType, @NotBlank String modelName,
        String provider, Integer priority, Boolean enabled, Integer canaryPercent) {
    public int safePriority() { return priority == null ? 0 : priority; }
    public boolean safeEnabled() { return enabled == null || enabled; }
    public int safeCanaryPercent() { return canaryPercent == null ? 0 : canaryPercent; }
}
