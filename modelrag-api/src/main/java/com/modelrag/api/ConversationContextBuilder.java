package com.modelrag.api;

/** Builds model input from persisted summaries, recent messages and scoped memories. */
public interface ConversationContextBuilder {
    ConversationContext build(String userId, long datasetId, Long conversationId, String question);

    /**
     * Auto routing supplies only its bounded Top3 metadata candidates here. Implementations
     * must never enumerate or fully retrieve every accessible knowledge base in a model call.
     */
    default ConversationContext build(String userId, long datasetId, Long conversationId, String question,
            java.util.List<DatasetCandidate> datasetCandidates) {
        return build(userId, datasetId, conversationId, question);
    }

    /** Rebinds dataset-scoped memory after auto routing without invoking the rewrite model again. */
    default ConversationContext scopeToDataset(String userId, long datasetId, String question,
            ConversationContext context) {
        return context;
    }

    record ConversationContext(String summary, java.util.List<Message> recentMessages, String memoryContext,
            String standaloneQuestion, RoutingDecision routingDecision) {
        public ConversationContext(String summary, java.util.List<Message> recentMessages, String memoryContext,
                String standaloneQuestion) {
            this(summary, recentMessages, memoryContext, standaloneQuestion,
                    RoutingDecision.ruleDirect(standaloneQuestion));
        }
    }

    record DatasetCandidate(long datasetId, String name, String description) { }

    /** One decision shared by rewrite, intent selection, knowledge-base routing and rerank. */
    record RoutingDecision(String intent, String standaloneQuestion, java.util.List<Long> datasetCandidates,
            java.util.List<String> missingSlots, double confidence, boolean requiresRerank, String riskLevel,
            boolean modelInvoked) {
        public RoutingDecision {
            datasetCandidates = datasetCandidates == null ? java.util.List.of() : java.util.List.copyOf(datasetCandidates);
            missingSlots = missingSlots == null ? java.util.List.of() : java.util.List.copyOf(missingSlots);
        }

        public static RoutingDecision ruleDirect(String question) {
            return new RoutingDecision("KNOWLEDGE_QA", question, java.util.List.of(), java.util.List.of(),
                    1.0, false, "LOW", false);
        }
    }

    record Message(String role, String content, long messageId) { }
}
