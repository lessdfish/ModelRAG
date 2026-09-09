package com.modelrag.agent.runtime;

import com.modelrag.agent.orchestrator.AgenticRetrievalEventSink;
import com.modelrag.qa.dto.QaRequest;

/** Runtime input; request data is copied into durable state before the first action. */
public record AgentStartCommand(QaRequest request, String executionId, String mode,
        String fallbackTool, AgenticRetrievalEventSink events) {
    public AgentStartCommand {
        if (request == null || executionId == null || executionId.isBlank() || mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("agent start command is invalid");
        }
        fallbackTool = fallbackTool == null ? "" : fallbackTool;
        events = events == null ? AgenticRetrievalEventSink.NOOP : events;
    }

    public AgentStartCommand(QaRequest request, String executionId, String mode, String fallbackTool) {
        this(request, executionId, mode, fallbackTool, AgenticRetrievalEventSink.NOOP);
    }
}
