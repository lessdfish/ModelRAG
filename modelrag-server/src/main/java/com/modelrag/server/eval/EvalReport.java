package com.modelrag.server.eval;
import java.util.List;

public record EvalReport(
        int total,
        double recallAt5,
        double recallAt20,
        double mrr,
        double contextPrecision,
        double contextRecall,
        double answerRelevance,
        double ndcg,
        double refusalRate,
        double refusalAccuracy,
        double answerAccuracy,
        double faithfulness,
        EvalParameters parameters,
        List<EvalCaseResult> caseResults,
        List<String> badCases) {

    public EvalReport {
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        badCases = badCases == null ? List.of() : List.copyOf(badCases);
    }
}
