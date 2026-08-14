package com.modelrag.server.eval;

import java.util.List;

public record EvalParameters(
        long datasetId,
        String datasetName,
        long revision,
        int chunkSize,
        int chunkOverlap,
        int topK,
        double threshold,
        String judgeMode,
        String evaluatedAt,
        List<EvalJudgeModeCount> judgeModeCounts,
        int judgeFallbacks,
        int judgeEvaluatedCases,
        String judgeRequestedMode) {

    public EvalParameters {
        judgeModeCounts = judgeModeCounts == null ? List.of() : List.copyOf(judgeModeCounts);
    }

    public EvalParameters withJudge(List<EvalJudgeModeCount> counts, int fallbacks,
            int evaluatedCases, String requestedMode) {
        return new EvalParameters(datasetId, datasetName, revision, chunkSize, chunkOverlap, topK, threshold,
                judgeMode, evaluatedAt, counts, fallbacks, evaluatedCases, requestedMode);
    }
}
