package com.modelrag.agent.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable versioned durable state for one non-direct agent execution. */
public record AgentState(int stateVersion, String executionId, String mode, String userId,
        Set<String> userRoles, long datasetId, Long conversationId, String goal,
        AgentRuntimeStatus status, int currentStep, int maxSteps, Instant deadlineAt,
        AgentBudgetState budgets, List<AgentObservedSource> observedSources,
        List<AgentObservationSnapshot> observations, List<AgentEvidenceSnapshot> evidence,
        Map<String, Object> toolState, AgentPendingAction pendingAction, String approvalId,
        List<String> degradedComponents, AgentResultSnapshot result, long checkpointSeq) {
    public static final int CURRENT_STATE_VERSION = 1;
    public static final int MAX_OBSERVED_SOURCES = 64;
    public static final int MAX_OBSERVATIONS = 20;
    public static final int MAX_EVIDENCE = 32;

    public AgentState {
        if (stateVersion != CURRENT_STATE_VERSION || executionId == null || executionId.isBlank()
                || mode == null || mode.isBlank() || userId == null || userId.isBlank() || datasetId <= 0
                || goal == null || goal.isBlank() || status == null || currentStep < 0 || maxSteps <= 0
                || currentStep > maxSteps || deadlineAt == null || budgets == null || checkpointSeq < 0) {
            throw new IllegalArgumentException("agent state is invalid");
        }
        goal = bounded(goal, 4_000);
        userRoles = userRoles == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(userRoles));
        observedSources = boundedList(observedSources, MAX_OBSERVED_SOURCES);
        observations = boundedList(observations, MAX_OBSERVATIONS);
        evidence = boundedList(evidence, MAX_EVIDENCE);
        toolState = toolState == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(toolState));
        approvalId = approvalId == null ? "" : approvalId;
        degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream()
                .filter(value -> value != null && !value.isBlank()).map(value -> bounded(value, 120))
                .distinct().limit(16).toList();
    }

    public static AgentState initial(String executionId, String mode, String userId, long datasetId,
            Long conversationId, String goal, int maxSteps, Instant deadlineAt, AgentBudgetState budgets) {
        return new AgentState(CURRENT_STATE_VERSION, executionId, mode, userId, Set.of(), datasetId,
                conversationId, goal, AgentRuntimeStatus.RUNNING, 0, maxSteps, deadlineAt, budgets,
                List.of(), List.of(), List.of(), Map.of(), null, "", List.of(), null, 0);
    }

    public Builder toBuilder() { return new Builder(this); }

    public AgentState withCheckpointSeq(long value) { return toBuilder().checkpointSeq(value).build(); }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static <T> List<T> boundedList(List<T> value, int max) {
        if (value == null || value.isEmpty()) return List.of();
        return List.copyOf(new ArrayList<>(value.subList(0, Math.min(value.size(), max))));
    }

    /** Small builder used by runtime transitions without exposing mutable state. */
    public static final class Builder {
        private int stateVersion;
        private String executionId;
        private String mode;
        private String userId;
        private Set<String> userRoles;
        private long datasetId;
        private Long conversationId;
        private String goal;
        private AgentRuntimeStatus status;
        private int currentStep;
        private int maxSteps;
        private Instant deadlineAt;
        private AgentBudgetState budgets;
        private List<AgentObservedSource> observedSources;
        private List<AgentObservationSnapshot> observations;
        private List<AgentEvidenceSnapshot> evidence;
        private Map<String, Object> toolState;
        private AgentPendingAction pendingAction;
        private String approvalId;
        private List<String> degradedComponents;
        private AgentResultSnapshot result;
        private long checkpointSeq;

        private Builder(AgentState state) {
            stateVersion = state.stateVersion; executionId = state.executionId; mode = state.mode;
            userId = state.userId; userRoles = state.userRoles; datasetId = state.datasetId;
            conversationId = state.conversationId; goal = state.goal; status = state.status;
            currentStep = state.currentStep; maxSteps = state.maxSteps; deadlineAt = state.deadlineAt;
            budgets = state.budgets; observedSources = state.observedSources; observations = state.observations;
            evidence = state.evidence; toolState = state.toolState; pendingAction = state.pendingAction;
            approvalId = state.approvalId; degradedComponents = state.degradedComponents;
            result = state.result; checkpointSeq = state.checkpointSeq;
        }

        public Builder status(AgentRuntimeStatus value) { status = value; return this; }
        public Builder currentStep(int value) { currentStep = value; return this; }
        public Builder budgets(AgentBudgetState value) { budgets = value; return this; }
        public Builder observedSources(List<AgentObservedSource> value) { observedSources = value; return this; }
        public Builder observations(List<AgentObservationSnapshot> value) { observations = value; return this; }
        public Builder evidence(List<AgentEvidenceSnapshot> value) { evidence = value; return this; }
        public Builder toolState(Map<String, Object> value) { toolState = value; return this; }
        public Builder pendingAction(AgentPendingAction value) { pendingAction = value; return this; }
        public Builder approvalId(String value) { approvalId = value; return this; }
        public Builder degradedComponents(List<String> value) { degradedComponents = value; return this; }
        public Builder result(AgentResultSnapshot value) { result = value; return this; }
        public Builder checkpointSeq(long value) { checkpointSeq = value; return this; }
        public AgentState build() {
            return new AgentState(stateVersion, executionId, mode, userId, userRoles, datasetId, conversationId,
                    goal, status, currentStep, maxSteps, deadlineAt, budgets, observedSources, observations,
                    evidence, toolState, pendingAction, approvalId, degradedComponents, result, checkpointSeq);
        }
    }
}
