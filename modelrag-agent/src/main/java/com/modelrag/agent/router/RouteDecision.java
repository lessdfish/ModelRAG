package com.modelrag.agent.router;

public enum RouteDecision {
    DIRECT_RAG,
    AGENTIC_RAG,
    TOOL_AGENT,

    /** @deprecated use AGENTIC_RAG for knowledge work or TOOL_AGENT for business tools. */
    @Deprecated AGENT,
    /** @deprecated use AGENTIC_RAG. */
    @Deprecated HYBRID
}
