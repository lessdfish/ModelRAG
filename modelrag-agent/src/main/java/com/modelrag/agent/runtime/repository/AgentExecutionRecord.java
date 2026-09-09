package com.modelrag.agent.runtime.repository;

import java.time.Instant;

/** Durable execution summary and lease projection. */
public record AgentExecutionRecord(String executionId, String userId, long datasetId,
        Long conversationId, String mode, String goal, String status, int currentStep,
        int maxSteps, Instant deadlineAt, int stateVersion, long lastCheckpointSeq,
        String leaseOwner, Instant leaseUntil, String errorCode, String errorMessage,
        Instant finishedAt) { }
