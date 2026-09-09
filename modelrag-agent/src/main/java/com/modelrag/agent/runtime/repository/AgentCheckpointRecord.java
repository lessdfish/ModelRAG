package com.modelrag.agent.runtime.repository;

import java.time.Instant;

/** Durable checkpoint row; it is separate from the observability step trace. */
public record AgentCheckpointRecord(String executionId, long checkpointSeq, int stateVersion,
        String status, String stateJson, Instant createdAt) { }
