package com.modelrag.qa.evidence;

import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import com.modelrag.qa.sanitizer.ContextSanitizer;
import com.modelrag.qa.sanitizer.ContextWindowManager;
import com.modelrag.qa.sanitizer.StructuredPromptBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Formats only bounded, labelled EvidenceSet content for the final model call. */
@Service
public class EvidenceContextAssembler {
    private final ContextSanitizer sanitizer;
    private final ContextWindowManager window;
    private final StructuredPromptBuilder prompts;
    private final int maxTokens;

    public EvidenceContextAssembler(ContextSanitizer sanitizer, ContextWindowManager window) {
        this(sanitizer, window, new StructuredPromptBuilder(), 1600);
    }

    @Autowired
    public EvidenceContextAssembler(ContextSanitizer sanitizer, ContextWindowManager window,
            StructuredPromptBuilder prompts,
            @Value("${modelrag.qa.context-max-tokens:1600}") int maxTokens) {
        this.sanitizer = sanitizer;
        this.window = window;
        this.prompts = prompts;
        this.maxTokens = Math.max(256, maxTokens);
    }

    public String evidenceContext(EvidenceSet evidenceSet) {
        if (evidenceSet == null) return "";
        return fitEvidence(evidenceSet, maxTokens);
    }

    public String prompt(String query, ConversationContext conversation, EvidenceSet evidenceSet) {
        List<String> parts = new ArrayList<>();
        String conversationText = conversationText(conversation);
        if (!conversationText.isBlank()) parts.add(conversationText);
        if (evidenceSet != null) {
            int evidenceTokens = conversationText.isBlank() ? maxTokens : Math.max(256, maxTokens * 3 / 4);
            parts.add(fitEvidence(evidenceSet, evidenceTokens));
        }
        String context = window.fit(parts, maxTokens);
        return prompts.build(sanitizer.sanitize(query), context);
    }

    private String fitEvidence(EvidenceSet evidenceSet, int tokenBudget) {
        int evidenceCount = evidenceSet.evidence().size();
        if (evidenceCount == 0) return "";
        int contentBudget = Math.max(160, tokenBudget * 4 / evidenceCount - 96);
        List<String> formatted = evidenceSet.evidence().stream()
                .map(evidence -> format(evidence, contentBudget)).toList();
        return window.fit(formatted, tokenBudget);
    }

    private String format(Evidence evidence, int contentBudget) {
        StringBuilder result = new StringBuilder();
        result.append('[').append(evidence.evidenceId()).append("]\n");
        result.append("Document: ").append(sanitizer.sanitize(evidence.documentName())).append('\n');
        if (!evidence.titlePath().isBlank()) {
            result.append("Path: ").append(sanitizer.sanitize(evidence.titlePath())).append('\n');
        }
        EvidenceLocator locator = evidence.locator();
        if (locator.pageFrom() != null) {
            result.append("Page: ").append(locator.pageFrom());
            if (!locator.pageFrom().equals(locator.pageTo())) result.append('-').append(locator.pageTo());
            result.append('\n');
        }
        String content = sanitizer.sanitize(evidence.content());
        if (content.length() > contentBudget) content = content.substring(0, contentBudget);
        result.append("Content:\n").append(content);
        return result.toString();
    }

    private String conversationText(ConversationContext context) {
        if (context == null) return "";
        List<String> parts = new ArrayList<>();
        if (context.summary() != null && !context.summary().isBlank()) {
            parts.add("会话摘要（仅作上下文）：\n" + sanitizer.sanitize(context.summary()));
        }
        if (context.recentMessages() != null && !context.recentMessages().isEmpty()) {
            parts.add("最近消息（仅作上下文）：\n" + context.recentMessages().stream()
                    .map(message -> sanitizer.sanitize(message.role()) + ": " + sanitizer.sanitize(message.content()))
                    .collect(Collectors.joining("\n")));
        }
        if (context.memoryContext() != null && !context.memoryContext().isBlank()) {
            parts.add(sanitizer.sanitize(context.memoryContext()));
        }
        return String.join("\n", parts);
    }
}
