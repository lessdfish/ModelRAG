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

    /** Requests cancellation without inferring ownership from a local thread map. */
    default boolean requestCancellation(String executionId) { return false; }

    /** Finalizes a cancellation only when PostgreSQL says this execution is unowned. */
    default boolean finalizeCancellationIfUnowned(String executionId) { return false; }

    /** Finalizes a bounded batch of cancellation requests whose lease has expired. */
    default int finalizeExpiredCancellations(int limit) { return 0; }

    List<String> findRecoverable(int limit);
}
