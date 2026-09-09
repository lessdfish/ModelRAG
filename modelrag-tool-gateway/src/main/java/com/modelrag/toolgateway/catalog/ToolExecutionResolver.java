package com.modelrag.toolgateway.catalog;

/** Narrow internal resolver used only by the gateway execution facade. */
public interface ToolExecutionResolver {
    ToolExecutionSpec resolveForExecution(String name);
}
