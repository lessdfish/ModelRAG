package com.modelrag.qa.orchestrator;

import com.modelrag.api.ConversationContextBuilder;
import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import com.modelrag.qa.sanitizer.ContextSanitizer;
import com.modelrag.qa.sanitizer.ContextWindowManager;
import com.modelrag.qa.sanitizer.StructuredPromptBuilder;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.search.dto.ScoredChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds the bounded, persisted conversation and evidence context passed to a model. */
@Service
public class ContextAssembler {
    private final ConversationContextBuilder contexts;
    private final ContextSanitizer sanitizer;
    private final ContextWindowManager window;
    private final StructuredPromptBuilder prompts;
    private final int maxTokens;

    public ContextAssembler(ConversationContextBuilder contexts, ContextSanitizer sanitizer,
            ContextWindowManager window, StructuredPromptBuilder prompts,
            @Value("${modelrag.qa.context-max-tokens:1600}") int maxTokens) {
        this.contexts = contexts;
        this.sanitizer = sanitizer;
        this.window = window;
        this.prompts = prompts;
        this.maxTokens = Math.max(256, maxTokens);
    }

    public ContextBundle build(QaRequest request, String sanitizedQuestion) {
        if (request.resolvedContext() != null) {
            return new ContextBundle(request.resolvedContext(), request.resolvedContext().standaloneQuestion());
        }
        ConversationContext context = contexts.build(request.userId(), request.datasetId(),
                request.conversationId(), sanitizedQuestion);
        return new ContextBundle(context, context.standaloneQuestion());
    }

    public String prompt(String query, ConversationContext context, List<ScoredChunk> evidence) {
        return prompts.build(query, fit(context, evidence));
    }

    public String fit(ConversationContext context, List<ScoredChunk> evidence) {
        List<String> parts = new ArrayList<>();
        String conversation = conversationText(context);
        if (!conversation.isBlank()) parts.add(conversation);
        if (evidence != null) {
            parts.addAll(evidence.stream().filter(Objects::nonNull)
                    .map(ScoredChunk::content).map(sanitizer::sanitize).toList());
        }
        return window.fit(parts, maxTokens);
    }

    public String fit(List<String> values) {
        return window.fit(values == null ? List.of() : values, maxTokens);
    }

    public String conversationText(ConversationContext context) {
        if (context == null) return "";
        List<String> parts = new ArrayList<>();
        if (context.summary() != null && !context.summary().isBlank()) {
            parts.add("会话摘要（仅作上下文）：\n" + context.summary());
        }
        if (context.recentMessages() != null && !context.recentMessages().isEmpty()) {
            parts.add("最近 8 条消息（仅作上下文）：\n" + context.recentMessages().stream()
                    .map(message -> message.role() + ": " + message.content()).collect(Collectors.joining("\n")));
        }
        if (context.memoryContext() != null && !context.memoryContext().isBlank()) parts.add(context.memoryContext());
        return String.join("\n", parts);
    }

    public record ContextBundle(ConversationContext conversation, String standaloneQuestion) { }
}
