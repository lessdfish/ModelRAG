package com.modelrag.api;

/**
 * Stable, framework-light facade reserved for the Java SDK and Spring Boot Starter.
 */
public interface KnowledgeAssistant {
    AnswerResponse ask(AskRequest request);

    record AskRequest(long datasetId, String question, Long conversationId, String userId) {
    }

    record AnswerResponse(String answer, java.util.List<Citation> citations, boolean refused, String traceId) {
    }

    record Citation(long chunkId, String documentName, String location, String excerpt) {
    }
}
