package com.modelrag.agent.memory;

import com.modelrag.api.ConversationContextBuilder;
import com.modelrag.api.ConversationRepository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Adapter exposing the PostgreSQL conversation store through the framework-light API contract. */
@Service
@Profile("!test")
public class PostgresConversationRepository implements ConversationRepository {
    private final ConversationMemory memory;

    public PostgresConversationRepository(ConversationMemory memory) {
        this.memory = memory;
    }

    @Override
    public long create(String userId, Long datasetId, String title) {
        return memory.create(userId, datasetId, title);
    }

    @Override
    public List<Conversation> list(String userId, boolean includeArchived) {
        return memory.conversations(userId, includeArchived).stream()
                .map(item -> new Conversation(item.id(), item.datasetId(), item.title(), item.messageCount(), item.updateTime()))
                .toList();
    }

    @Override
    public List<ConversationContextBuilder.Message> recentMessages(String userId, long conversationId, int limit) {
        return memory.recent(userId, conversationId, limit).stream()
                .map(item -> new ConversationContextBuilder.Message(item.role(), item.content(), item.messageId()))
                .toList();
    }

    @Override
    public void append(String userId, long conversationId, String role, String content) {
        memory.append(userId, conversationId, role, content, "[]", null, "rag", null);
    }
}
