package com.modelrag.agent.retrieval;

/** Read-only knowledge capabilities available to the agentic retrieval loop. */
public enum RetrievalActionName {
    SEARCH_KNOWLEDGE,
    OPEN_NODE,
    FIND_IN_DOCUMENT,
    READ_PARENT,
    READ_NEIGHBORS,
    READ_CHILDREN,
    FOLLOW_REFERENCES,
    FINISH
}
