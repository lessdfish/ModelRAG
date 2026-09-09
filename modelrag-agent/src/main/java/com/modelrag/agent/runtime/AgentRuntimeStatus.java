package com.modelrag.agent.runtime;

/** Durable lifecycle states for a non-direct agent execution. */
public enum AgentRuntimeStatus {
    RUNNING,
    WAITING_APPROVAL,
    DONE,
    ERROR,
    TIMEOUT,
    CANCEL_REQUESTED,
    CANCELLED,
    RECONCILIATION_REQUIRED
}
