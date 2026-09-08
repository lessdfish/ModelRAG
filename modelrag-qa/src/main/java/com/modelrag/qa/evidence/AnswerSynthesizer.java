package com.modelrag.qa.evidence;

import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import com.modelrag.api.UserModelProvider;
import com.modelrag.qa.sanitizer.ContextSanitizer;
import com.modelrag.qa.sanitizer.OutputGuard;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Performs at most one final synthesis from one EvidenceSet and nothing else. */
@Service
public class AnswerSynthesizer {
    public static final String INSUFFICIENT_EVIDENCE = "当前知识库没有足够证据回答该问题。";

    private final EvidenceContextAssembler context;
    private final OutputGuard outputGuard;
    private final ContextSanitizer sanitizer;
    private final ObjectProvider<UserModelProvider> userModels;

    public AnswerSynthesizer(EvidenceContextAssembler context, OutputGuard outputGuard,
            ContextSanitizer sanitizer, ObjectProvider<UserModelProvider> userModels) {
        this.context = context;
        this.outputGuard = outputGuard;
        this.sanitizer = sanitizer;
        this.userModels = userModels;
    }

    public AnswerDraft synthesize(String userId, String query, ConversationContext conversation,
            EvidenceSet evidenceSet, Consumer<String> tokenConsumer) {
        String promptContext = context.evidenceContext(evidenceSet);
        String prompt = context.prompt(query, conversation, evidenceSet);
        if (evidenceSet == null || !evidenceSet.sufficiency().sufficient()) {
            return new AnswerDraft(INSUFFICIENT_EVIDENCE, prompt, promptContext, "refusal", "", false);
        }
        String fallback = fallback(evidenceSet);
        try {
            UserModelProvider provider = userModels.getIfAvailable();
            if (provider == null || !provider.configured(userId)) {
                return new AnswerDraft(fallback, prompt, promptContext,
                        "local-evidence-no-user-model", "", false);
            }
            String generated = generate(provider, userId, prompt, tokenConsumer);
            if (generated.isBlank() || generated.startsWith("[mock]") || generated.startsWith("[fallback]")
                    || !outputGuard.safe(generated)) {
                return new AnswerDraft(fallback, prompt, promptContext, "local-evidence", generated, true);
            }
            return new AnswerDraft(compactGenerated(generated), prompt, promptContext,
                    "user-model", generated, true);
        } catch (RuntimeException error) {
            return new AnswerDraft(fallback, prompt, promptContext, "local-fallback", "", true);
        }
    }

    private String generate(UserModelProvider provider, String userId, String prompt, Consumer<String> consumer) {
        if (consumer == null) return provider.generate(userId, prompt).trim();
        StringBuilder output = new StringBuilder();
        provider.stream(userId, prompt, chunk -> {
            if (chunk == null || chunk.isEmpty() || !outputGuard.safeFragment(chunk)) return;
            output.append(chunk);
            consumer.accept(chunk);
        });
        return output.toString().trim();
    }

    private String fallback(EvidenceSet evidenceSet) {
        String content = evidenceSet.evidence().stream().filter(Evidence::primary)
                .map(Evidence::content).filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        content = sanitizer.sanitize(content).replaceAll("\\s+", " ").trim();
        if (content.isBlank()) return INSUFFICIENT_EVIDENCE;
        return "根据证据：" + limit(content, 360);
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

    public record AnswerDraft(String answer, String finalPrompt, String promptContext,
            String answerSource, String modelOutput, boolean modelCalled) {
        public AnswerDraft(String answer, String finalPrompt, String promptContext,
                String answerSource, String modelOutput) {
            this(answer, finalPrompt, promptContext, answerSource, modelOutput, false);
        }
    }
}
