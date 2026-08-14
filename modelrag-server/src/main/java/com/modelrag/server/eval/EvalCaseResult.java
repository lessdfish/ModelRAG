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
        String judgeMode) {

    public EvalCaseResult {
        actionHints = actionHints == null ? List.of() : List.copyOf(actionHints);
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
        vectorChunkIds = vectorChunkIds == null ? List.of() : List.copyOf(vectorChunkIds);
        bm25ChunkIds = bm25ChunkIds == null ? List.of() : List.copyOf(bm25ChunkIds);
        fusedChunkIds = fusedChunkIds == null ? List.of() : List.copyOf(fusedChunkIds);
        finalChunkIds = finalChunkIds == null ? List.of() : List.copyOf(finalChunkIds);
    }
}
