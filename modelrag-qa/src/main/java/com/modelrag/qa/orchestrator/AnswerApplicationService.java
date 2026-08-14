package com.modelrag.qa.orchestrator;

import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import com.modelrag.api.UserModelProvider;
import com.modelrag.qa.sanitizer.ContextSanitizer;
import com.modelrag.qa.sanitizer.OutputGuard;
import com.modelrag.search.dto.ScoredChunk;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Generates an answer from already assembled evidence; it never owns retrieval or persistence. */
@Service
public class AnswerApplicationService {
    private static final String INSUFFICIENT_EVIDENCE = "当前知识库没有足够证据回答该问题。";
    private final ContextAssembler context;
    private final OutputGuard outputGuard;
    private final ContextSanitizer sanitizer;
    private final ObjectProvider<UserModelProvider> userModels;

    public AnswerApplicationService(ContextAssembler context, OutputGuard outputGuard, ContextSanitizer sanitizer,
            ObjectProvider<UserModelProvider> userModels) {
        this.context = context;
        this.outputGuard = outputGuard;
        this.sanitizer = sanitizer;
        this.userModels = userModels;
    }

    public AnswerDraft answer(String userId, String query, String fallback, boolean refused,
            ConversationContext conversation, List<ScoredChunk> evidence, Consumer<String> tokenConsumer) {
        String promptContext = context.fit(conversation, evidence);
        String prompt = context.prompt(query, conversation, evidence);
        if (refused) return new AnswerDraft(INSUFFICIENT_EVIDENCE, prompt, promptContext, "refusal", "");
        if (extractiveQuery(query)) return new AnswerDraft(fallback, prompt, promptContext, "local-evidence", "");
        try {
            UserModelProvider userModel = userModels.getIfAvailable();
            if (userModel == null || !userModel.configured(userId)) {
                return new AnswerDraft(fallback, prompt, promptContext, "local-evidence-no-user-model", "");
            }
            String generated = generate(userModel, userId, prompt, tokenConsumer);
            if (generated.isBlank() || generated.startsWith("[mock]") || generated.startsWith("[fallback]")
                    || !outputGuard.safe(generated)) {
                return new AnswerDraft(fallback, prompt, promptContext, "local-evidence", generated);
            }
            return new AnswerDraft(compactGenerated(generated), prompt, promptContext, "user-model", generated);
        } catch (RuntimeException error) {
            return new AnswerDraft(fallback, prompt, promptContext, "local-fallback", "");
        }
    }

    private String generate(UserModelProvider provider, String userId, String prompt, Consumer<String> consumer) {
        if (consumer == null) return provider.generate(userId, prompt).trim();
        StringBuilder output = new StringBuilder();
        provider.stream(userId, prompt, chunk -> append(output, chunk, consumer));
        return output.toString().trim();
    }

    private void append(StringBuilder output, String chunk, Consumer<String> consumer) {
        if (chunk == null || chunk.isEmpty() || !outputGuard.safeFragment(chunk)) return;
        output.append(chunk);
        consumer.accept(chunk);
    }

    private boolean extractiveQuery(String query) {
        String value = query == null ? "" : query;
        return value.contains("文档规定") || value.contains("制度规定") || value.contains("规定了什么")
                || value.contains("要求是什么") || value.contains("有哪些要求");
    }

    private String compactGenerated(String answer) {
        String clean = sanitizer.sanitize(answer).replaceAll("(?m)^\\s*(问|问题)[:：].*$", "").trim();
        StringBuilder result = new StringBuilder();
        for (String sentence : clean.split("(?<=[。！？])|\\n+")) {
            String value = sentence.trim();
            if (value.isBlank() || value.startsWith("问：") || value.startsWith("问:")) continue;
            if (result.length() + value.length() > 360) break;
            result.append(value);
            if (result.length() >= 220) break;
        }
        return result.isEmpty() ? limit(clean, 360) : result.toString();
    }

    private String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }

    public record AnswerDraft(String answer, String finalPrompt, String promptContext, String answerSource,
            String modelOutput) { }
}
