package com.modelrag.agent.runtime;

/** Remaining deterministic runtime budgets. */
public record AgentBudgetState(int remainingSteps, int remainingSearchActions,
        int remainingNavigationActions, int remainingToolActions) {
    public AgentBudgetState {
        if (remainingSteps < 0 || remainingSearchActions < 0 || remainingNavigationActions < 0
                || remainingToolActions < 0) {
            throw new IllegalArgumentException("agent budgets must not be negative");
        }
    }

    public static AgentBudgetState of(int steps, int searches, int navigation) {
        return new AgentBudgetState(steps, searches, navigation, steps);
    }

    public AgentBudgetState consumeStep() {
        return new AgentBudgetState(Math.max(0, remainingSteps - 1), remainingSearchActions,
                remainingNavigationActions, remainingToolActions);
    }

    public AgentBudgetState consumeSearch() {
        return new AgentBudgetState(remainingSteps, Math.max(0, remainingSearchActions - 1),
                remainingNavigationActions, remainingToolActions);
    }

    public AgentBudgetState consumeNavigation() {
        return new AgentBudgetState(remainingSteps, remainingSearchActions,
                Math.max(0, remainingNavigationActions - 1), remainingToolActions);
    }

    public AgentBudgetState consumeTool() {
        return new AgentBudgetState(remainingSteps, remainingSearchActions,
                remainingNavigationActions, Math.max(0, remainingToolActions - 1));
    }
}
