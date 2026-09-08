package com.modelrag.search.orchestrator;

import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.reranker.Reranker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Private compatibility boundary from the legacy reranker type to V2 candidates. */
public class RetrievalCandidateReranker {
    private final Reranker delegate;

    public RetrievalCandidateReranker(Reranker delegate) {
        this.delegate = delegate;
    }

    public RerankResult rerank(long datasetId, String query, List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return new RerankResult(List.of(), false);
        Map<Long, RetrievalCandidate> byUnit = new HashMap<>();
        List<ScoredChunk> legacy = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            if (candidate == null || byUnit.putIfAbsent(candidate.retrievalUnitId(), candidate) != null) continue;
            legacy.add(new ScoredChunk(candidate.retrievalUnitId(), candidate.content(), candidate.score(),
                    "v2-fused", legacy.size() + 1));
        }
        List<ScoredChunk> reranked = delegate.rerank(datasetId, query, legacy);
        if (reranked == null) throw new IllegalStateException("reranker returned null");

        Set<Long> seen = new HashSet<>();
        List<RetrievalCandidate> result = new ArrayList<>();
        boolean invalidIdentity = false;
        for (ScoredChunk value : reranked) {
            RetrievalCandidate candidate = byUnit.get(value.chunkId());
            if (candidate == null || !seen.add(value.chunkId())) {
                invalidIdentity = true;
                continue;
            }
            result.add(new RetrievalCandidate(candidate.datasetId(), candidate.retrievalUnitId(), candidate.nodeId(),
                    candidate.documentId(), candidate.documentVersionId(), candidate.indexBuildId(), candidate.unitType(),
                    candidate.titlePath(), candidate.content(), value.score(), RetrievalChannel.RERANK,
                    result.size() + 1, candidate.metadata()));
            if ("local-rerank".equals(value.channel())) invalidIdentity = true;
        }
        if (result.size() < byUnit.size()) {
            for (RetrievalCandidate candidate : candidates) {
                if (seen.add(candidate.retrievalUnitId())) {
                    result.add(new RetrievalCandidate(candidate.datasetId(), candidate.retrievalUnitId(), candidate.nodeId(),
                            candidate.documentId(), candidate.documentVersionId(), candidate.indexBuildId(), candidate.unitType(),
                            candidate.titlePath(), candidate.content(), candidate.score(), RetrievalChannel.RERANK,
                            result.size() + 1, candidate.metadata()));
                }
            }
        }
        return new RerankResult(List.copyOf(result), invalidIdentity);
    }

    public record RerankResult(List<RetrievalCandidate> candidates, boolean degraded) {
        public RerankResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
