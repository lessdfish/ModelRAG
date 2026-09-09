package com.modelrag.agent.policy;

import com.modelrag.agent.retrieval.RetrievalObservation;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceSufficiency;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded execution-local state supplied to the retrieval policy. */
public record AgentPolicyInput(String userId, String goal, List<RetrievalObservation> observations,
        List<Evidence> evidence, EvidenceSufficiency sufficiency, Set<Long> observedNodeIds,
        Set<Long> observedDocumentIds, int step, int remainingSteps, int remainingSearchActions,
        int remainingNavigationActions) {
    public static final int MAX_OBSERVATIONS = 20;

    public AgentPolicyInput {
        if (goal == null || goal.isBlank() || sufficiency == null || step < 0
                || remainingSteps < 0 || remainingSearchActions < 0 || remainingNavigationActions < 0) {
            throw new IllegalArgumentException("policy input is invalid");
        }
        userId = userId == null ? "" : userId;
        observations = observations == null ? List.of() : observations.stream()
                .filter(value -> value != null).limit(MAX_OBSERVATIONS).toList();
        evidence = evidence == null ? List.of() : evidence.stream().filter(value -> value != null)
                .limit(64).toList();
        observedNodeIds = copyIds(observedNodeIds);
        observedDocumentIds = copyIds(observedDocumentIds);
    }

    public AgentPolicyInput(String goal, List<RetrievalObservation> observations, List<Evidence> evidence,
            EvidenceSufficiency sufficiency, int step, int remainingSteps, int remainingSearchActions,
            int remainingNavigationActions) {
        this("", goal, observations, evidence, sufficiency, Set.of(), Set.of(), step,
                remainingSteps, remainingSearchActions, remainingNavigationActions);
    }

    private static Set<Long> copyIds(Set<Long> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Long value : values) if (value != null && value > 0) result.add(value);
        return Collections.unmodifiableSet(result);
    }
}
