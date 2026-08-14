package com.modelrag.agent.memory;

import com.modelrag.common.event.QaAnsweredEvent;
import com.modelrag.api.MemorySuggestionService;
import com.modelrag.api.LongTermMemoryStore;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component @Profile("!test") public class QaAnswerMemoryListener {
    private static final Logger LOG = LoggerFactory.getLogger(QaAnswerMemoryListener.class);
    private final ConversationMemory memory; private final LongTermMemoryService longTerm; private final MemorySuggestionService suggestions;
    public QaAnswerMemoryListener(ConversationMemory memory,LongTermMemoryService longTerm,MemorySuggestionService suggestions){this.memory=memory;this.longTerm=longTerm;this.suggestions=suggestions;}
    @EventListener public void onAnswer(QaAnsweredEvent event){
        if (!event.userMessagePersisted()) memory.append(event.userId(), event.conversationId(), "user", event.question(), "[]", null, event.mode(), event.datasetName());
        memory.append(event.userId(), event.conversationId(), "assistant", event.answer(), event.citations(), event.traceId(), event.mode(), event.datasetName());
        if (event.userId() == null || event.userId().isBlank()) return;
        try {
            suggestions.suggest(event.userId(), event.datasetId(), event.question(), event.answer(),
                    String.valueOf(event.conversationId()), null).stream()
                    .filter(java.util.Objects::nonNull)
                    .map(item -> pending(item, event.conversationId()))
                    .forEach(longTerm::upsert);
        } catch (RuntimeException error) {
            // Suggestions are secondary to the answer and must never turn a completed answer into a failure.
            LOG.warn("无法保存待确认长期记忆建议, conversationId={}", event.conversationId(), error);
        }
    }

    private LongTermMemoryService.Memory pending(LongTermMemoryStore.Memory item, Long conversationId) {
        Long sourceMessageId = item == null || item.id() == null ? null : parseLong(item.id());
        return new LongTermMemoryService.Memory(null, item.userId(), item.datasetId(), item.scope(), item.type(),
                item.memoryKey(), item.content(), "PENDING_CONFIRMATION", .5, .8,
                item.expiresAt(), conversationId, sourceMessageId);
    }

    private Long parseLong(String value) {
        try { return Long.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }
}
