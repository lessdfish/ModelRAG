package com.modelrag.agent.runtime;

/** Narrow mode boundary; AgentRuntime owns lease/checkpoint/status semantics. */
public interface AgentModeHandler {
    String mode();

    AgentModeDecision decide(AgentState state);

    AgentState execute(AgentState state, AgentPendingAction action);
}
