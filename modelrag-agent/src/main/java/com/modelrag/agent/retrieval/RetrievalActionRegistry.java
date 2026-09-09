package com.modelrag.agent.retrieval;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Dispatches only whitelisted read-only retrieval actions and consumes execution budgets. */
@Service
public class RetrievalActionRegistry {
    private final Map<RetrievalActionName, RetrievalActionExecutor> executors;

    @Autowired
    public RetrievalActionRegistry(List<RetrievalActionExecutor> actions) {
        this((Collection<RetrievalActionExecutor>) actions);
    }

    public RetrievalActionRegistry(Collection<RetrievalActionExecutor> actions) {
        EnumMap<RetrievalActionName, RetrievalActionExecutor> values = new EnumMap<>(RetrievalActionName.class);
        if (actions != null) {
            for (RetrievalActionExecutor executor : actions) {
                if (executor == null || executor.action() == null || executor.action() == RetrievalActionName.FINISH) {
                    throw new IllegalArgumentException("invalid retrieval action executor");
                }
                if (values.put(executor.action(), executor) != null) {
                    throw new IllegalArgumentException("duplicate retrieval action: " + executor.action());
                }
            }
        }
        this.executors = Map.copyOf(values);
    }

    public Set<RetrievalActionName> actions() { return executors.keySet(); }

    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        if (request == null || context == null) throw new IllegalArgumentException("retrieval request/context required");
        if (request.action() == RetrievalActionName.FINISH) {
            throw new IllegalArgumentException("FINISH is a policy decision, not a retrieval action");
        }
        RetrievalActionExecutor executor = executors.get(request.action());
        if (executor == null) throw new IllegalArgumentException("retrieval action is not allowed");
        if (!request.arguments().keySet().stream().allMatch(executor.allowedArguments()::contains)) {
            throw new IllegalArgumentException("retrieval action argument is not allowed");
        }
        if (!context.consumeStep()) {
            return new RetrievalObservation(request.action(), "已达到 Agent 步数上限", List.of(), List.of(),
                    List.of("step-budget"), 0);
        }
        return executor.execute(request, context);
    }
}
