package com.modelrag.toolgateway.execution;

/** Cancellation boundary exposed to Agent; gateway implementations own worker details. */
@FunctionalInterface
public interface ToolExecutionCanceller {
    boolean cancel(Thread agentOwner);
}
