package com.modelrag.agent.auto;

import com.modelrag.agent.orchestrator.AgentOrchestrator;
import com.modelrag.agent.orchestrator.AgentResult;
import com.modelrag.agent.intent.IntentNode;
import com.modelrag.agent.intent.IntentTreeService;
import com.modelrag.agent.router.ComplexityRouter;
import com.modelrag.agent.router.RouteDecision;
import com.modelrag.api.ConversationContextBuilder;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

@Service
public class AutoQaService {
    private final DatasetRepository datasets;
    private final SearchFacade search;
    private final ComplexityRouter router;
    private final QaOrchestrator qa;
    private final AgentOrchestrator agent;
    private final IntentTreeService intents;
    private final ConversationContextBuilder contexts;

    public AutoQaService(DatasetRepository datasets, SearchFacade search,
                         ComplexityRouter router, QaOrchestrator qa,
                         AgentOrchestrator agent, IntentTreeService intents, ConversationContextBuilder contexts) {
        this.datasets = datasets;
        this.search = search;
        this.router = router;
        this.qa = qa;
        this.agent = agent;
        this.intents = intents;
        this.contexts = contexts;
    }

    public AutoQaResult answer(AutoQaRequest request) {
        return answer(request, Set.of());
    }

    public AutoQaResult answer(AutoQaRequest request, Set<Long> allowedDatasetIds) {
        return answer(request, allowedDatasetIds, "global");
    }

    public AutoQaResult answer(AutoQaRequest request, Set<Long> allowedDatasetIds, String userId) {
        return answer(request, allowedDatasetIds, userId, Set.of());
    }

    public AutoQaResult answer(AutoQaRequest request, Set<Long> allowedDatasetIds, String userId, Set<String> userRoles) {
        return answer(request, allowedDatasetIds, userId, userRoles, null);
    }

    public AutoQaResult answer(AutoQaRequest request, Set<Long> allowedDatasetIds, String userId, Set<String> userRoles,
                               Consumer<String> tokenConsumer) {
        return answer(request, allowedDatasetIds, userId, userRoles, tokenConsumer, null, null);
    }

    public AutoQaResult answer(AutoQaRequest request, Set<Long> allowedDatasetIds, String userId, Set<String> userRoles,
                               Consumer<String> tokenConsumer, String executionId, Consumer<String> agentStarted) {
        if (request.query() == null || request.query().isBlank()) throw new IllegalArgumentException("问题不能为空");
        String routedQuestion = request.query();
        ConversationContextBuilder context = contexts;
        List<Dataset> preselected = datasets.route(request.query(), allowedDatasetIds, 3);
        ConversationContextBuilder.ConversationContext resolvedContext = null;
        {
            List<ConversationContextBuilder.DatasetCandidate> routingCandidates = preselected.stream()
                    .map(dataset -> new ConversationContextBuilder.DatasetCandidate(dataset.id(), dataset.name(),
                            dataset.description()))
                    .toList();
            resolvedContext = context.build(userId, 0, request.conversationId(), request.query(), routingCandidates);
            routedQuestion = resolvedContext.standaloneQuestion();
            if (resolvedContext.routingDecision() != null) {
                var decision = resolvedContext.routingDecision();
                if (!decision.missingSlots().isEmpty() || decision.confidence() < .55
                        || "HIGH".equalsIgnoreCase(decision.riskLevel())) {
                    return clarification(decision.missingSlots());
                }
            }
        }
        Candidate candidate;
        try {
            List<Long> selectedIds = resolvedContext == null || resolvedContext.routingDecision() == null
                    ? List.of() : resolvedContext.routingDecision().datasetCandidates();
            candidate = selectCandidate(routedQuestion, allowedDatasetIds, preselected, selectedIds);
        } catch (IllegalStateException unavailable) {
            return new AutoQaResult(0, "未匹配知识库", RouteDecision.DIRECT_RAG, "REFUSED",
                    "没有找到当前用户可访问且已完成索引的知识库。请确认权限范围，或先上传并完成文档索引。",
                    List.of(), 0, true, null, null, null, List.of("AUTO_DATASET_UNAVAILABLE", "REFUSED"));
        }
        if (!candidate.confident()) {
            return new AutoQaResult(0, "未匹配知识库", RouteDecision.DIRECT_RAG, "REFUSED",
                    "没有找到与问题足够匹配的知识库。请补充相关文档，或把问题范围描述得更具体。",
                    List.of(), 0, true, null, null, null, List.of("AUTO_DATASET_LOW_CONFIDENCE", "REFUSED"));
        }
        Dataset dataset = candidate.dataset();
        RouteDecision route = route(candidate, resolvedContext, request.query());
        if (resolvedContext != null) {
            resolvedContext = context.scopeToDataset(userId, dataset.id(), routedQuestion, resolvedContext);
        }
        QaRequest qaRequest = new QaRequest(dataset.id(), request.query(), request.conversationId(), userId,
                userRoles == null ? Set.of() : userRoles).withResolvedContext(resolvedContext);
        if (route == RouteDecision.AGENT) {
            AgentResult result;
            if (executionId == null || executionId.isBlank()) {
                result = agent.execute(qaRequest);
            } else {
                agent.registerExecution(qaRequest, executionId);
                if (agentStarted != null) agentStarted.accept(executionId);
                result = agent.executeRegistered(qaRequest, executionId);
            }
            return new AutoQaResult(dataset.id(), dataset.name(), result.route(), result.status(), result.answer(),
                    result.citations(), result.confidence(), result.refused(), result.traceId(), result.executionId(), result.approvalId(),
                    result.steps(), result.degradedComponents());
        }
        QaResult result = tokenConsumer == null ? qa.answer(qaRequest) : qa.answer(qaRequest, tokenConsumer);
        return new AutoQaResult(dataset.id(), dataset.name(), route, result.refused() ? "REFUSED" : "DONE",
                result.answer(), result.citations(), result.confidence(), result.refused(), result.traceId(), null, null,
                List.of("AUTO_DATASET", "DIRECT_RAG", "DONE"), result.degradedComponents());
    }

    public Dataset selectDataset(String query) {
        return selectDataset(query, Set.of());
    }

    public Dataset selectDataset(String query, Set<Long> allowedDatasetIds) {
        Candidate candidate = selectCandidate(query, allowedDatasetIds, datasets.route(query, allowedDatasetIds, 3), List.of());
        if (!candidate.confident()) throw new IllegalStateException("没有匹配到足够相关的知识库");
        return candidate.dataset();
    }

    private Candidate selectCandidate(String query, Set<Long> allowedDatasetIds, List<Dataset> initialCandidates,
                                      List<Long> structuredCandidateIds) {
        List<Dataset> preselected = orderedCandidates(
                expandStructuredCandidates(initialCandidates, structuredCandidateIds, allowedDatasetIds),
                structuredCandidateIds);
        Candidate intentCandidate = selectIntentCandidate(query, preselected);
        if (intentCandidate != null && intentCandidate.route() == RouteDecision.AGENT) return intentCandidate;
        if (preselected.isEmpty()) throw new IllegalStateException("没有可用知识库，请先上传并完成索引");
        // Metadata/name routing preselects at most three candidates; only the best
        // candidate enters the formal hybrid retrieval path once.
        Candidate contentCandidate = score(preselected.get(0), query);
        return contentCandidate.confident() || intentCandidate == null ? contentCandidate : intentCandidate;
    }

    private List<Dataset> expandStructuredCandidates(List<Dataset> initialCandidates, List<Long> structuredIds,
                                                     Set<Long> allowedDatasetIds) {
        List<Dataset> values = new ArrayList<>(initialCandidates == null ? List.of() : initialCandidates);
        if (structuredIds == null || structuredIds.isEmpty()) return values;
        Set<Long> indexed = datasets.findIndexedDatasetIds();
        for (Long id : structuredIds.stream().distinct().toList()) {
            if (id == null || values.stream().anyMatch(dataset -> dataset.id() == id)) continue;
            if (allowedDatasetIds != null && !allowedDatasetIds.isEmpty() && !allowedDatasetIds.contains(id)) continue;
            if (!indexed.contains(id)) continue;
            try {
                values.add(datasets.findById(id));
            } catch (RuntimeException ignored) {
                // A stale model candidate must not break auto routing.
            }
        }
        return values;
    }

    private List<Dataset> orderedCandidates(List<Dataset> initialCandidates, List<Long> structuredIds) {
        List<Dataset> values = initialCandidates == null ? List.of() : initialCandidates;
        if (structuredIds == null || structuredIds.isEmpty()) return values;
        List<Dataset> selected = structuredIds.stream().distinct()
                .flatMap(id -> values.stream().filter(dataset -> dataset.id() == id).findFirst().stream()).toList();
        return selected.isEmpty() ? values : selected;
    }

    private int metadataRelevance(Dataset dataset, String query) {
        return relevance(query, dataset.name() + " " + (dataset.description() == null ? "" : dataset.description()));
    }

    private Candidate bestContentCandidate(List<Candidate> candidates) {
        int bestEvidence = candidates.stream().filter(Candidate::confident).mapToInt(Candidate::evidence).max().orElse(-1);
        if (bestEvidence >= 0) {
            return candidates.stream()
                    .filter(Candidate::confident)
                    .filter(candidate -> candidate.evidence() >= Math.max(1, bestEvidence - 3))
                    .max(Comparator.comparing(candidate -> candidate.dataset().id()))
                    .orElseThrow();
        }
        return candidates.stream()
                .max(Comparator.comparingDouble(AutoQaService::scoreBucket).thenComparing(candidate -> candidate.dataset().id()))
                .orElseThrow();
    }

    private Candidate selectIntentCandidate(String query, List<Dataset> candidates) {
        return candidates.stream()
                .map(dataset -> intents.match(dataset.id(), query)
                        .filter(intent -> indexedIntent(dataset, intent))
                        .map(intent -> new Candidate(dataset, 10_000 + intent.priority(), 2, 0, 0, route(intent)))
                        .orElse(null))
                .filter(candidate -> candidate != null)
                .max(Comparator.comparingDouble(AutoQaService::scoreBucket).thenComparing(candidate -> candidate.dataset().id()))
                .orElse(null);
    }

    private RouteDecision route(Candidate candidate, ConversationContextBuilder.ConversationContext context,
                                String originalQuestion) {
        if (candidate.route() != null) return candidate.route();
        if (context != null && context.routingDecision() != null && context.routingDecision().modelInvoked()) {
            String intent = context.routingDecision().intent().toUpperCase(java.util.Locale.ROOT);
            if (intent.contains("TOOL") || intent.contains("WRITE") || intent.contains("ACTION")) {
                return RouteDecision.AGENT;
            }
            return RouteDecision.DIRECT_RAG;
        }
        return router.route(originalQuestion);
    }

    private AutoQaResult clarification(List<String> missingSlots) {
        String suffix = missingSlots == null || missingSlots.isEmpty() ? "" : " 缺少：" + String.join("、", missingSlots) + "。";
        return new AutoQaResult(0, "待澄清", RouteDecision.DIRECT_RAG, "REFUSED",
                "当前问题依赖的上下文或必要参数不足，请补充完整问题。" + suffix,
                List.of(), 0, true, null, null, null, List.of("ROUTING_CLARIFICATION"),
                List.of("ROUTING_LOW_CONFIDENCE", "REFUSED"));
    }

    private static double scoreBucket(Candidate candidate) {
        return Math.floor(candidate.score() * 10) / 10;
    }

    private boolean indexedIntent(Dataset dataset, IntentNode intent) {
        return "TOOL".equals(intent.targetType()) || datasets.findIndexedDatasetIds().contains(dataset.id());
    }

    private RouteDecision route(IntentNode intent) {
        return "TOOL".equals(intent.targetType()) ? RouteDecision.AGENT : RouteDecision.DIRECT_RAG;
    }

    private Candidate score(Dataset dataset, String query) {
        try {
            SearchStages stages = search.inspect(new HybridSearchRequest(dataset.id(), query, Math.max(3, dataset.topK())));
            double vector = stages.vectorResults().isEmpty() ? 0 : normalize(stages.vectorResults().get(0).score());
            double fused = stages.fusedResults().isEmpty() ? 0 : stages.fusedResults().get(0).score() * 100;
            double lexical = stages.bm25Results().isEmpty() ? 0 : Math.min(2, stages.bm25Results().get(0).score() / 10);
            int evidence = stages.fusedResults().stream().limit(3).mapToInt(chunk -> relevance(query, chunk.content())).max().orElse(0);
            int metadata = relevance(query, dataset.name() + " " + (dataset.description() == null ? "" : dataset.description()));
            return new Candidate(dataset, vector + fused + lexical + metadata * 0.35 + evidence * 2.0, evidence, lexical, metadata, null);
        } catch (RuntimeException ignored) {
            int metadata = relevance(query, dataset.name() + " " + (dataset.description() == null ? "" : dataset.description()));
            return new Candidate(dataset, metadata * 0.35, 0, 0, metadata, null);
        }
    }

    private double normalize(double score) {
        if (score <= 0) return 0;
        if (score <= 1) return score;
        return Math.min(1, Math.log10(score + 1));
    }

    private int relevance(String query, String value) {
        String normalized = query.replaceAll("[\\s，。！？、：:]+", "");
        int score = 0;
        for (int i = 0; i + 1 < normalized.length(); i++) {
            String pair = normalized.substring(i, i + 2);
            if (stopPair(pair)) continue;
            if (value.contains(pair)) score++;
        }
        return score;
    }

    private boolean stopPair(String pair) {
        return Set.of("的是", "什么", "多少", "有几", "几天", "怎么", "如何", "可以", "能够",
                "需要", "申请", "审批", "流程", "周期", "问题", "查询").contains(pair);
    }

    private record Candidate(Dataset dataset, double score, int evidence, double lexical, int metadata,
                             RouteDecision route) {
        boolean confident() {
            return evidence >= 2 || (lexical > 0 && evidence >= 1) || (metadata >= 2 && (evidence >= 1 || lexical > 0));
        }
    }
}
