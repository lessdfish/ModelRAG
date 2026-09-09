package com.modelrag.agent.runtime;

/** Raised when another owner advanced the durable checkpoint first. */
public class AgentCheckpointConflictException extends IllegalStateException {
    public AgentCheckpointConflictException(String message) { super(message); }
}
