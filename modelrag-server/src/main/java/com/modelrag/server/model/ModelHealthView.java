package com.modelrag.server.model;

import java.time.Instant;

/** Public read model for model health and routing policy. */
public record ModelHealthView(String modelType, String modelName, String provider, String state,
        int priority, boolean enabled, int canaryPercent, int failures, Instant nextProbeAt,
        boolean availableRuntime) {
    public ModelHealthView withAvailableRuntime(boolean value) {
        return new ModelHealthView(modelType, modelName, provider, state, priority, enabled,
                canaryPercent, failures, nextProbeAt, value);
    }
}
