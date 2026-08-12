package com.modelrag.server.eval;
import java.util.List;
import java.util.Map;
public record EvalReport(int total,double recallAt5,double recallAt20,double mrr,double contextPrecision,double contextRecall,double answerRelevance,double ndcg,double refusalRate,double refusalAccuracy,double answerAccuracy,double faithfulness,Map<String,Object> parameters,List<Map<String,Object>> caseResults,List<String> badCases) { }
