package com.modelrag.agent.retrieval;

import java.util.Set;

/** Executor contract for one read-only retrieval action. */
public interface RetrievalActionExecutor {
    RetrievalActionName action();

    RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context);

    default Set<String> allowedArguments() { return Set.of(); }
}
