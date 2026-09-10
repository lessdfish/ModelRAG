package com.modelrag.server.eval;

import java.util.List;

public record EvalCaseResult(
        String question,
        String category,
        String sourceTraceId,
        String failureStage,
        String diagnosis,
        List<String> actionHints,
        List<Long> expectedChunkIds,
        List<Long> vectorChunkIds,
        List<Long> bm25ChunkIds,
        List<Long> fusedChunkIds,
        List<Long> finalChunkIds,
        int rankAtFinal,
        int rankAtFused,
        boolean hitAt5,
        boolean hitAt20,
        boolean refused,
        boolean shouldRefuse,
        boolean answerMatched,
        double faithfulness,
        double answerRelevance,
        String judgeMode,
        String variant,
        List<Long> expectedDocumentIds,
        List<Long> expectedNodeIds,
        List<EvalEvidenceGroup> expectedEvidenceGroups,
        List<Long> finalDocumentIds,
        List<Long> finalNodeIds,
        List<Long> finalRetrievalUnitIds,
        List<Long> semanticResultIds,
        List<Long> lexicalResultIds,
        List<Long> rerankedResultIds,
        int documentRank,
        int nodeRank,
        boolean error,
        boolean degraded,
        long latencyMs,
        int actionCount,
        int navigationCount,
        java.util.Map<String, String> metricStatus) {

    public EvalCaseResult {
        actionHints = actionHints == null ? List.of() : List.copyOf(actionHints);
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
        vectorChunkIds = vectorChunkIds == null ? List.of() : List.copyOf(vectorChunkIds);
        bm25ChunkIds = bm25ChunkIds == null ? List.of() : List.copyOf(bm25ChunkIds);
        fusedChunkIds = fusedChunkIds == null ? List.of() : List.copyOf(fusedChunkIds);
        finalChunkIds = finalChunkIds == null ? List.of() : List.copyOf(finalChunkIds);
        variant = variant == null || variant.isBlank() ? "V1" : variant;
        expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        expectedNodeIds = expectedNodeIds == null ? List.of() : List.copyOf(expectedNodeIds);
        expectedEvidenceGroups = expectedEvidenceGroups == null ? List.of() : List.copyOf(expectedEvidenceGroups);
        finalDocumentIds = finalDocumentIds == null ? List.of() : List.copyOf(finalDocumentIds);
        finalNodeIds = finalNodeIds == null ? List.of() : List.copyOf(finalNodeIds);
        finalRetrievalUnitIds = finalRetrievalUnitIds == null ? List.of() : List.copyOf(finalRetrievalUnitIds);
        semanticResultIds = semanticResultIds == null ? List.of() : List.copyOf(semanticResultIds);
        lexicalResultIds = lexicalResultIds == null ? List.of() : List.copyOf(lexicalResultIds);
        rerankedResultIds = rerankedResultIds == null ? List.of() : List.copyOf(rerankedResultIds);
        metricStatus = metricStatus == null ? java.util.Map.of() : java.util.Map.copyOf(metricStatus);
    }

    /** Compatibility constructor for the V1 response shape. */
    public EvalCaseResult(String question, String category, String sourceTraceId, String failureStage,
            String diagnosis, List<String> actionHints, List<Long> expectedChunkIds, List<Long> vectorChunkIds,
            List<Long> bm25ChunkIds, List<Long> fusedChunkIds, List<Long> finalChunkIds, int rankAtFinal,
            int rankAtFused, boolean hitAt5, boolean hitAt20, boolean refused, boolean shouldRefuse,
            boolean answerMatched, double faithfulness, double answerRelevance, String judgeMode) {
        this(question, category, sourceTraceId, failureStage, diagnosis, actionHints, expectedChunkIds,
                vectorChunkIds, bm25ChunkIds, fusedChunkIds, finalChunkIds, rankAtFinal, rankAtFused, hitAt5,
                hitAt20, refused, shouldRefuse, answerMatched, faithfulness, answerRelevance, judgeMode, "V1",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                0, 0, false, false, 0, 0, 0, java.util.Map.of());
    }
}
