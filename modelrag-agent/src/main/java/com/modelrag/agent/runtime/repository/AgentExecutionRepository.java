package com.modelrag.agent.runtime.repository;

import com.modelrag.agent.runtime.AgentState;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Durable execution row port. Implementations must use PostgreSQL as the source of truth. */
public interface AgentExecutionRepository {
    void create(AgentState state);

    Optional<AgentExecutionRecord> findById(String executionId);

    boolean tryClaim(String executionId, String owner, Duration lease);

    default boolean tryClaimWaiting(String executionId, String owner, Duration lease) { return false; }

    boolean renew(String executionId, String owner, Duration lease);

    boolean release(String executionId, String owner);

    List<String> findRecoverable(int limit);
}
