package com.modelrag.agent.runtime.repository;

import com.modelrag.agent.runtime.AgentState;
import java.util.Optional;

/** Atomic execution-summary/checkpoint persistence port. */
public interface AgentCheckpointRepository {
    boolean save(AgentState state, String stateJson, String leaseOwner);

    Optional<AgentCheckpointRecord> latest(String executionId);
}
