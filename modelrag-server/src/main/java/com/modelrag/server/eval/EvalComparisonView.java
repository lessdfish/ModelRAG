package com.modelrag.server.eval;

import java.util.List;

public record EvalComparisonView(
        String variant,
        int topK,
        int total,
        int answerable,
        double recallAtFinal,
        double recallAtFused,
        double mrr,
        double contextPrecision,
        double contextRecall,
        double ndcg,
        List<String> badCases,
        List<EvalCaseResult> caseResults,
        EvalParameters parameters) {

    public EvalComparisonView {
        badCases = badCases == null ? List.of() : List.copyOf(badCases);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
    }
}
