package com.modelrag.toolgateway.coordination;

import java.time.Duration;

/** Coordination state boundary for rate limits, circuits and short-lived tool state. */
public interface ToolCoordinationStore {
    void ensureAvailable();

    String circuit(String toolName);

    void saveCircuit(String toolName, String state, int failures, long openedUntil, Duration ttl);

    long incrementRate(String toolName, Duration window);
}
