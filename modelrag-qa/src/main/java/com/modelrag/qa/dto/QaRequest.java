package com.modelrag.qa.dto;
import java.util.Set;
public record QaRequest(long datasetId, String query, Long conversationId, String userId, Set<String> userRoles) {
    public QaRequest(long datasetId, String query, Long conversationId) {
        this(datasetId, query, conversationId, "global");
    }

    public QaRequest(long datasetId, String query, Long conversationId, String userId) {
        this(datasetId, query, conversationId, userId, Set.of());
    }
}
