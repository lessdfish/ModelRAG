package com.modelrag.api;

public interface MemorySuggestionService {
    java.util.List<LongTermMemoryStore.Memory> suggest(String userId, Long datasetId, String question, String answer,
            String sourceConversationId, String sourceMessageId);
}
