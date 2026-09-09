package com.modelrag.agent.orchestrator;

import java.util.Map;

/** Bounded user-visible lifecycle events for one execution-local retrieval action. */
@FunctionalInterface
public interface AgenticRetrievalEventSink {
    AgenticRetrievalEventSink NOOP = (type, message, data) -> { };

    void publish(String type, String message, Map<String, Object> data);
}
