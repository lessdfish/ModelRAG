package com.modelrag.agent.runtime;

/** One deterministic handler decision. A pending action is persisted before it runs. */
public record AgentModeDecision(AgentPendingAction pendingAction, AgentResultSnapshot terminalResult) {
    public AgentModeDecision {
        if (pendingAction != null && terminalResult != null) {
            throw new IllegalArgumentException("a decision cannot be both action and terminal result");
        }
    }

    public static AgentModeDecision action(AgentPendingAction action) {
        return new AgentModeDecision(action, null);
    }

    public static AgentModeDecision terminal(AgentResultSnapshot result) {
        if (result == null) throw new IllegalArgumentException("terminal result is required");
        return new AgentModeDecision(null, result);
    }

    public boolean terminal() { return terminalResult != null; }
}
