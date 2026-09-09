package com.modelrag.toolgateway.execution;

import java.util.List;

/** Safe result returned by the in-process Tool Gateway. */
public record ToolInvocationResult(String toolName, String output, int attempts, boolean reused,
        long latencyMs, List<String> degradedComponents) {
    public ToolInvocationResult {
        output = output == null ? "" : output;
        degradedComponents = degradedComponents == null ? List.of() : List.copyOf(degradedComponents);
    }

    public ToolInvocationResult(String toolName, String output, int attempts, boolean reused, long latencyMs) {
        this(toolName, output, attempts, reused, latencyMs, List.of());
    }
}
