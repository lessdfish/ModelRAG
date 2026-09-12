package com.modelrag.server.eval;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.common.observability.RetrievalTraceContext;
import com.modelrag.common.observability.RetrievalTraceSession;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.common.observability.TraceCorrelation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaExecutionSnapshot;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** V1 adapter. It preserves chunk labels and resolves document identities for comparable V1 metrics. */
public class V1EvaluationAdapter {
    private final SearchFacade search;
    private final QaOrchestrator qa;
    private final ChunkRepository chunks;
    private final RetrievalTraceSink traceSink;

    public V1EvaluationAdapter(SearchFacade search, QaOrchestrator qa, ChunkRepository chunks) {
        this(search, qa, chunks, RetrievalTraceSink.NOOP);
    }

    public V1EvaluationAdapter(SearchFacade search, QaOrchestrator qa, ChunkRepository chunks,
            RetrievalTraceSink traceSink) {
        this.search = search;
        this.qa = qa;
        this.chunks = chunks;
        this.traceSink = traceSink == null ? RetrievalTraceSink.NOOP : traceSink;
    }

    public EvaluationObservation run(long datasetId, EvalItem item, int topK, double threshold) {
        long started = System.nanoTime();
        SearchStages stages;
        QaResult answer;
        RetrievalTraceContext context = RetrievalTraceContext.create("V1", datasetId, null, "legacy");
        RetrievalTraceSession session = RetrievalTraceSession.start(traceSink, context, "redacted");
        try (TraceCorrelation.Scope ignored = TraceCorrelation.bind(context, session)) {
            QaExecutionSnapshot snapshot = qa.executeV1ForEvaluation(
                    new QaRequest(datasetId, item.question(), null).withoutConversationMessage(), topK, threshold);
            stages = snapshot.retrievalStages();
            answer = snapshot.result();
            session.complete(answer.refused(), answer.degradedComponents());
        } catch (RuntimeException error) {
            session.fail("evaluation-v1-failure", List.of("EVALUATION_FAILURE"));
            return new EvaluationObservation("V1", "", List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), "", false, true, false, elapsed(started), 0, 0, null, null, Map.of());
        }
        List<Long> allIds = new ArrayList<>();
        addIds(allIds, stages.vectorResults());
        addIds(allIds, stages.bm25Results());
        addIds(allIds, stages.fusedResults());
        addIds(allIds, stages.finalResults());
        Map<Long, Long> documentByChunk = chunks.findActiveByIds(datasetId, allIds.stream().distinct().limit(500).toList())
                .stream().collect(Collectors.toMap(Chunk::id, Chunk::documentId, (left, right) -> left));
        List<Long> finalChunks = ids(stages.finalResults());
        List<Long> fusedChunks = ids(stages.fusedResults());
        List<Long> finalDocuments = documents(finalChunks, documentByChunk);
        List<Long> fusedDocuments = documents(fusedChunks, documentByChunk);
        Map<String, Long> latency = stages.latencyMs();
        boolean degraded = !stages.degradedComponents().isEmpty()
                || (answer.degradedComponents() != null && !answer.degradedComponents().isEmpty());
        return new EvaluationObservation("V1", context.traceId(), finalChunks, fusedChunks, finalDocuments,
                fusedDocuments, List.of(), List.of(), List.of(), List.of(), ids(stages.vectorResults()),
                ids(stages.bm25Results()), ids(stages.rerankResults()), answer.answer(), answer.refused(), false,
                degraded, Math.max(elapsed(started), latency.getOrDefault("total", 0L)),
                session.actionCount(), 0, null, null, latency, evidence(finalChunks, documentByChunk));
    }

    private List<ObservedEvidenceIdentity> evidence(List<Long> chunkIds, Map<Long, Long> documentByChunk) {
        List<ObservedEvidenceIdentity> result = new ArrayList<>();
        for (Long chunkId : chunkIds) {
            Long documentId = documentByChunk.get(chunkId);
            if (documentId != null) result.add(new ObservedEvidenceIdentity(documentId, null, null, null,
                    result.size() + 1, true));
        }
        return List.copyOf(result);
    }

    private void addIds(List<Long> target, List<ScoredChunk> values) {
        if (values != null) values.stream().filter(value -> value != null).map(ScoredChunk::chunkId).forEach(target::add);
    }

    private List<Long> ids(List<ScoredChunk> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null).map(ScoredChunk::chunkId)
                .distinct().toList();
    }

    private List<Long> documents(List<Long> chunkIds, Map<Long, Long> documentByChunk) {
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (Long id : chunkIds) {
            Long documentId = documentByChunk.get(id);
            if (documentId != null) values.add(documentId);
        }
        return List.copyOf(values);
    }

    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
}
