package com.modelrag.api;

public interface ConversationRepository {
    long create(String userId, Long datasetId, String title);
    java.util.List<Conversation> list(String userId, boolean includeArchived);
    java.util.List<ConversationContextBuilder.Message> recentMessages(String userId, long conversationId, int limit);
    void append(String userId, long conversationId, String role, String content);

    record Conversation(long id, Long datasetId, String title, int messageCount, java.time.Instant updatedAt) { }
}
