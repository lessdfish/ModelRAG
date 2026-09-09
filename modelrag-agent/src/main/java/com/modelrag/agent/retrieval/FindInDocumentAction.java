package com.modelrag.agent.retrieval;

import com.modelrag.qa.evidence.EvidenceRetrievalService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Indexed V2 document-scoped lookup constrained by an observed document ID. */
@Service
@Profile("!test")
public class FindInDocumentAction implements RetrievalActionExecutor {
    private final DocumentLexicalFindService finder;
    private final EvidenceRetrievalService evidenceRetrieval;
    private final int maxObservationItems;
    private final int maxExcerptChars;

    public FindInDocumentAction(DocumentLexicalFindService finder, EvidenceRetrievalService evidenceRetrieval) {
        this(finder, evidenceRetrieval, 20, 500);
    }

    @Autowired
    public FindInDocumentAction(DocumentLexicalFindService finder, EvidenceRetrievalService evidenceRetrieval,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-items:20}")
            int maxObservationItems,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-excerpt-chars:500}")
            int maxExcerptChars) {
        this.finder = finder;
        this.evidenceRetrieval = evidenceRetrieval;
        this.maxObservationItems = Math.max(1, Math.min(RetrievalObservation.MAX_ITEMS, maxObservationItems));
        this.maxExcerptChars = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxExcerptChars));
    }

    @Override public RetrievalActionName action() { return RetrievalActionName.FIND_IN_DOCUMENT; }
    @Override public Set<String> allowedArguments() { return Set.of("documentId", "query", "limit"); }

    @Override
    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        long started = System.nanoTime();
        long documentId = request.longInteger("documentId");
        context.requireObservedDocument(documentId);
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
            DocumentLexicalFindService.FindResult found = finder.find(context, documentId, query, limit);
            EvidenceRetrievalService.EvidenceRetrievalResult evidence = evidenceRetrieval.retrieve(
                    context.datasetId(), found.candidates().stream().limit(maxObservationItems).toList());
            degraded.addAll(found.degradedComponents());
            degraded.addAll(evidence.degradedComponents());
            return RetrievalActionSupport.fromEvidence(action(), evidence.primaryEvidence(), maxObservationItems,
                    maxExcerptChars, degraded.stream().distinct().toList(), started);
        } catch (RuntimeException error) {
            degraded.add("document-find-failed");
            return RetrievalActionSupport.empty(action(), "文档内 V2 搜索暂时不可用", degraded, started);
        }
    }
}
