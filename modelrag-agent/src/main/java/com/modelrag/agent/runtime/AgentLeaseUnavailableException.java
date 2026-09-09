package com.modelrag.agent.runtime;

/** Raised when this runtime cannot acquire the PostgreSQL execution lease. */
public class AgentLeaseUnavailableException extends IllegalStateException {
    public AgentLeaseUnavailableException(String message) { super(message); }
}
