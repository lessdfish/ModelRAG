package com.modelrag.agent.trace;

public record AgentStepTrace(String executionId, int stepIndex, String phase, String message, String data,
                             String status, long latencyMs, String createdAt) {
}
