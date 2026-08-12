package com.modelrag.agent.memory;

import com.modelrag.common.event.QaAnsweredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component public class QaAnswerMemoryListener {
    private final ConversationMemory memory; private final LongTermMemoryService longTerm; private final MemoryExtractor extractor;
    public QaAnswerMemoryListener(ConversationMemory memory,LongTermMemoryService longTerm,MemoryExtractor extractor){this.memory=memory;this.longTerm=longTerm;this.extractor=extractor;}
    @EventListener public void onAnswer(QaAnsweredEvent event){memory.append(event.conversationId(),"user",event.question());memory.append(event.conversationId(),"assistant",event.answer(),event.citations(),event.traceId(),event.mode(),event.datasetName());extractor.extract(event.userId(),event.question(),event.answer()).forEach(longTerm::upsert);}
}
