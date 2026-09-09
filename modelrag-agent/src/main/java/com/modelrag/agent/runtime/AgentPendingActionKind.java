package com.modelrag.agent.runtime;

/** Classification used to decide whether an in-flight action is safe to replay. */
public enum AgentPendingActionKind {
    RETRIEVAL,
    BUSINESS_TOOL,
    FINAL_SYNTHESIS
}
