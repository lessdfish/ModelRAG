package com.modelrag.toolgateway.catalog;

import java.util.Objects;

/** Internal gateway-only execution configuration; never pass this to Agent or API code. */
public record ToolExecutionSpec(ToolDescriptor descriptor, String authHeaderValue) {
    public ToolExecutionSpec {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    @Override
    public String toString() {
        return "ToolExecutionSpec[descriptor=" + descriptor.name()
                + ", hasAuthSecret=" + (authHeaderValue != null && !authHeaderValue.isBlank()) + "]";
    }
}
