package com.modelrag.agent.policy;

import com.modelrag.agent.retrieval.RetrievalActionName;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Whitelisted action schema exposed to the policy prompt. */
public record AgentActionDefinition(RetrievalActionName action, Set<String> argumentNames,
        String description) {
    public AgentActionDefinition {
        if (action == null || action == RetrievalActionName.FINISH) {
            throw new IllegalArgumentException("policy action is invalid");
        }
        argumentNames = argumentNames == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(argumentNames));
        description = description == null ? "" : description;
    }
}
