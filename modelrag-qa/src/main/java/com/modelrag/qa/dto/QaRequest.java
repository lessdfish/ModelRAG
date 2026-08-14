package com.modelrag.qa.dto;
import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import java.util.Set;
public record QaRequest(long datasetId, String query, Long conversationId, String userId, Set<String> userRoles,
        boolean persistConversationMessage, ConversationContext resolvedContext) {
    public QaRequest(long datasetId, String query, Long conversationId) {
        this(datasetId, query, conversationId, "global");
    }

    public QaRequest(long datasetId, String query, Long conversationId, String userId) {
        this(datasetId, query, conversationId, userId, Set.of());
    }

    public QaRequest(long datasetId, String query, Long conversationId, String userId, Set<String> userRoles) {
        this(datasetId, query, conversationId, userId, userRoles, true, null);
    }

    /** Internal Agent tool calls retain the conversation id for context but do not append a user turn. */
    public QaRequest withoutConversationMessage() {
        return new QaRequest(datasetId, query, conversationId, userId, userRoles, false, resolvedContext);
    }

    public QaRequest withResolvedContext(ConversationContext context) {
        return new QaRequest(datasetId, query, conversationId, userId, userRoles, persistConversationMessage, context);
    }
}
