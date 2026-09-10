package com.modelrag.server.eval;

import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaV2ApplicationService;
import com.modelrag.common.observability.RetrievalTraceContext;
import com.modelrag.common.observability.RetrievalTraceSession;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.common.observability.TraceCorrelation;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** V2 adapter. It evaluates projection identities separately from legacy chunk identities. */
public class V2EvaluationAdapter {
    private final HybridRetrievalService retrieval;
    private final QaV2ApplicationService qa;
    private final String embeddingProfile;
    private final RetrievalTraceSink traceSink;

    public V2EvaluationAdapter(HybridRetrievalService retrieval, QaV2ApplicationService qa,
            String embeddingProfile) {
        this(retrieval, qa, embeddingProfile, RetrievalTraceSink.NOOP);
    }

    public V2EvaluationAdapter(HybridRetrievalService retrieval, QaV2ApplicationService qa,
            String embeddingProfile, RetrievalTraceSink traceSink) {
        this.retrieval = retrieval;
        this.qa = qa;
        this.embeddingProfile = embeddingProfile == null || embeddingProfile.isBlank() ? "qwen3-v1" : embeddingProfile;
        this.traceSink = traceSink == null ? RetrievalTraceSink.NOOP : traceSink;
    }

    public EvaluationObservation run(long datasetId, EvalItem item, int topK, double threshold) {
        long started = System.nanoTime();
        RetrievalTraceContext context = RetrievalTraceContext.create("V2", datasetId, null, embeddingProfile);
        RetrievalTraceSession session = RetrievalTraceSession.start(traceSink, context, "redacted");
        try {
            RetrievalV2Stages stages;
            QaResult answer;
            try (TraceCorrelation.Scope ignored = TraceCorrelation.bind(context, session)) {
                stages = retrieval.inspect(new RetrievalV2Request(datasetId, item.question(), topK,
                        threshold, embeddingProfile));
                answer = qa.answer(new QaRequest(datasetId, item.question(), null).withoutConversationMessage(), null);
            }
            List<RetrievalCandidate> finalCandidates = stages.finalCandidates();
            List<RetrievalCandidate> fusedCandidates = stages.fusedCandidates();
            boolean degraded = !stages.degradedComponents().isEmpty()
                    || (answer.degradedComponents() != null && !answer.degradedComponents().isEmpty());
            session.complete(answer.refused(), answer.degradedComponents());
            return new EvaluationObservation("V2", context.traceId(), List.of(), List.of(),
                    documents(finalCandidates), documents(fusedCandidates), nodes(finalCandidates), nodes(fusedCandidates),
                    units(finalCandidates), units(fusedCandidates), units(stages.semanticCandidates()),
                    units(stages.lexicalCandidates()), units(stages.rerankedCandidates()), answer.answer(), answer.refused(),
                    answer.degradedComponents().contains("v2-failure"), degraded, Math.max(elapsed(started),
                    stages.latencyMs().getOrDefault("total", 0L)), session.actionCount(), 0, null, null,
                    stages.latencyMs());
        } catch (RuntimeException error) {
            session.fail("evaluation-v2-failure", List.of("EVALUATION_FAILURE"));
            return new EvaluationObservation("V2", "", List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), "", true, true, true, elapsed(started), 0, 0, null, null, Map.of());
        }
    }

    private List<Long> units(List<RetrievalCandidate> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null)
                .map(RetrievalCandidate::retrievalUnitId).distinct().toList();
    }

    private List<Long> documents(List<RetrievalCandidate> values) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (values != null) values.stream().filter(value -> value != null).map(RetrievalCandidate::documentId)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private List<Long> nodes(List<RetrievalCandidate> values) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (values != null) values.stream().filter(value -> value != null).map(RetrievalCandidate::nodeId)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
}
