package com.modelrag.search.shadow;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.config.V2EmbeddingProfileProvider;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Runs V2 after V1 and compares bounded document identity without changing the V1 response. */
@Service
@Profile("!test")
public class RetrievalShadowComparator {
    private final HybridRetrievalService v2;
    private final ChunkRepository chunks;
    private final MeterRegistry metrics;
    private final V2EmbeddingProfileProvider embeddingProfiles;

    public RetrievalShadowComparator(HybridRetrievalService v2, ChunkRepository chunks,
            MeterRegistry metrics) {
        this(v2, chunks, metrics, new V2EmbeddingProfileProvider());
    }

    @Autowired
    public RetrievalShadowComparator(HybridRetrievalService v2, ChunkRepository chunks,
            MeterRegistry metrics, V2EmbeddingProfileProvider embeddingProfiles) {
        this.v2 = v2;
        this.chunks = chunks;
        this.metrics = metrics;
        this.embeddingProfiles = embeddingProfiles;
    }

    public RetrievalShadowComparison compare(HybridSearchRequest request, List<ScoredChunk> v1Results,
            long v1LatencyMs) {
        try {
            Set<Long> v1Documents = resolveV1Documents(request.datasetId(), v1Results);
            long started = System.nanoTime();
            var v2Stages = v2.inspect(new RetrievalV2Request(request.datasetId(), request.query(),
                    request.topK(), request.threshold(), embeddingProfiles.profile()));
            long v2LatencyMs = elapsed(started);
            Set<Long> v2Documents = v2Stages.finalCandidates().stream()
                    .map(com.modelrag.search.dto.RetrievalCandidate::documentId).collect(java.util.stream.Collectors.toSet());
            double overlap = overlap(v1Documents, v2Documents);
            RetrievalShadowComparison result = new RetrievalShadowComparison(v1Results == null ? 0 : v1Results.size(),
                    v2Stages.finalCandidates().size(), v1LatencyMs, v2LatencyMs, overlap,
                    v2Stages.finalCandidates().isEmpty());
            metrics.counter("modelrag.retrieval.shadow.v2.success").increment();
            metrics.timer("modelrag.retrieval.shadow.v2_latency").record(v2LatencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            metrics.summary("modelrag.retrieval.shadow.document_overlap").record(overlap);
            if (result.v2Empty()) metrics.counter("modelrag.retrieval.shadow.v2_empty").increment();
            return result;
        } catch (RuntimeException error) {
            metrics.counter("modelrag.retrieval.shadow.v2.failure").increment();
            throw error;
        }
    }

    private Set<Long> resolveV1Documents(long datasetId, List<ScoredChunk> results) {
        if (results == null || results.isEmpty()) return Set.of();
        Set<Long> ids = results.stream().map(ScoredChunk::chunkId).filter(id -> id > 0).collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return Set.of();
        return chunks.findActiveByIds(datasetId, ids).stream().map(Chunk::documentId).collect(java.util.stream.Collectors.toSet());
    }

    private double overlap(Set<Long> first, Set<Long> second) {
        if (first.isEmpty() || second.isEmpty()) return 0;
        Set<Long> common = new HashSet<>(first);
        common.retainAll(second);
        return (double) common.size() / Math.min(first.size(), second.size());
    }

    private long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
