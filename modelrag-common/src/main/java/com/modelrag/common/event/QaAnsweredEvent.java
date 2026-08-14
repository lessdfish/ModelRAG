package com.modelrag.common.event;

public record QaAnsweredEvent(long conversationId, String question, String answer, String citations, String userId,
                              String traceId, String mode, String datasetName, boolean userMessagePersisted,
                              Long datasetId) {
    public QaAnsweredEvent(long conversationId, String question, String answer) {
        this(conversationId, question, answer, "[]", "global", null, null, null, false, null);
    }

    public QaAnsweredEvent(long conversationId, String question, String answer, String citations) {
        this(conversationId, question, answer, citations, "global", null, null, null, false, null);
    }

    public QaAnsweredEvent(long conversationId, String question, String answer, String citations, String userId) {
        this(conversationId, question, answer, citations, userId, null, null, null, false, null);
    }

    public QaAnsweredEvent(long conversationId, String question, String answer, String citations, String userId,
                           String traceId, String mode, String datasetName, boolean userMessagePersisted) {
        this(conversationId, question, answer, citations, userId, traceId, mode, datasetName,
                userMessagePersisted, null);
    }
}
