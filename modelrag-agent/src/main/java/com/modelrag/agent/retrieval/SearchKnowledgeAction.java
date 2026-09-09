package com.modelrag.agent.retrieval;

import com.modelrag.qa.evidence.EvidenceRetrievalService;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.search.config.V2EmbeddingProfileProvider;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** V2 semantic/lexical search action; it returns evidence and never an answer. */
@Service
@Profile("!test")
public class SearchKnowledgeAction implements RetrievalActionExecutor {
    private final HybridRetrievalService retrieval;
    private final EvidenceRetrievalService evidenceRetrieval;
    private final V2EmbeddingProfileProvider embeddingProfiles;
    private final DatasetRepository datasets;
    private final int maxObservationItems;
    private final int maxExcerptChars;

    public SearchKnowledgeAction(HybridRetrievalService retrieval, EvidenceRetrievalService evidenceRetrieval,
            V2EmbeddingProfileProvider embeddingProfiles) {
        this(retrieval, evidenceRetrieval, embeddingProfiles, null, 20, 500);
    }

    public SearchKnowledgeAction(HybridRetrievalService retrieval, EvidenceRetrievalService evidenceRetrieval,
            V2EmbeddingProfileProvider embeddingProfiles,
            DatasetRepository datasets) {
        this(retrieval, evidenceRetrieval, embeddingProfiles, datasets, 20, 500);
    }

    public SearchKnowledgeAction(HybridRetrievalService retrieval, EvidenceRetrievalService evidenceRetrieval,
            V2EmbeddingProfileProvider embeddingProfiles, int maxObservationItems, int maxExcerptChars) {
        this(retrieval, evidenceRetrieval, embeddingProfiles, null, maxObservationItems, maxExcerptChars);
    }

    @Autowired
    public SearchKnowledgeAction(HybridRetrievalService retrieval, EvidenceRetrievalService evidenceRetrieval,
            V2EmbeddingProfileProvider embeddingProfiles, DatasetRepository datasets,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-items:20}")
            int maxObservationItems,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-excerpt-chars:500}")
            int maxExcerptChars) {
        this.retrieval = retrieval;
        this.evidenceRetrieval = evidenceRetrieval;
        this.embeddingProfiles = embeddingProfiles;
        this.datasets = datasets;
        this.maxObservationItems = Math.max(1, Math.min(RetrievalObservation.MAX_ITEMS, maxObservationItems));
        this.maxExcerptChars = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxExcerptChars));
    }

    @Override public RetrievalActionName action() { return RetrievalActionName.SEARCH_KNOWLEDGE; }
    @Override public Set<String> allowedArguments() { return Set.of("query", "limit"); }

    @Override
    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        long started = System.nanoTime();
        String query = request.text("query", 2_000);
        int requestedLimit = request.integer("limit", 6);
        if (requestedLimit < 1) throw new IllegalArgumentException("limit must be positive");
        int limit = Math.min(maxObservationItems, requestedLimit);
        List<String> degraded = new ArrayList<>();
        if (limit != requestedLimit) degraded.add("limit-clamped");
        if (!context.consumeSearchAction()) {
            degraded.add("search-budget");
            return new RetrievalObservation(action(), "已达到搜索动作上限", List.of(), List.of(), degraded, 0);
        }
        try {
            if (datasets == null) throw new IllegalStateException("dataset repository is required");
            Dataset dataset = datasets.findById(context.datasetId());
            RetrievalV2Stages stages = retrieval.inspect(new RetrievalV2Request(context.datasetId(), query, limit,
                    dataset.threshold(), embeddingProfiles.profile()));
            EvidenceRetrievalService.EvidenceRetrievalResult result = evidenceRetrieval.retrieve(
                    context.datasetId(), stages.finalCandidates());
            degraded.addAll(stages.degradedComponents());
            degraded.addAll(result.degradedComponents());
            return RetrievalActionSupport.fromEvidence(action(), result.primaryEvidence(), maxObservationItems,
                    maxExcerptChars, degraded.stream().distinct().toList(), started);
        } catch (RuntimeException error) {
            degraded.add("search-failed");
            return RetrievalActionSupport.empty(action(), "V2 搜索暂时不可用", degraded, started);
        }
    }
}
