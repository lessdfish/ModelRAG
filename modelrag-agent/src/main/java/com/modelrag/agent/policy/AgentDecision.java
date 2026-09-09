package com.modelrag.agent.policy;

import com.modelrag.agent.retrieval.RetrievalActionName;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict policy output; deliberately has no reasoning or chain-of-thought field. */
public record AgentDecision(AgentDecisionType type, RetrievalActionName action,
        Map<String, Object> arguments) {
    public AgentDecision {
        if (type == null) throw new IllegalArgumentException("decision type is required");
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        if (type == AgentDecisionType.FINISH && (action != null || !arguments.isEmpty())) {
            throw new IllegalArgumentException("FINISH cannot contain an action or arguments");
        }
        if (type == AgentDecisionType.ACTION
                && (action == null || action == RetrievalActionName.FINISH)) {
            throw new IllegalArgumentException("ACTION must select a retrieval action");
        }
    }

    public static AgentDecision finish() {
        return new AgentDecision(AgentDecisionType.FINISH, null, Map.of());
    }

    public static AgentDecision action(RetrievalActionName action, Map<String, Object> arguments) {
        return new AgentDecision(AgentDecisionType.ACTION, action, arguments);
    }
}
