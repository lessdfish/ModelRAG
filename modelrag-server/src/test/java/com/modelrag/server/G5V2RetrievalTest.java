package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.common.vector.SearchResult;
import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.knowledge.model.ActiveBuildRef;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.search.channel.v2.ActiveBuildScopeResolver;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.channel.v2.SemanticSearchPort;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import com.modelrag.search.orchestrator.RetrievalCandidateReranker;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G5V2RetrievalTest {
    @Test
    void hybridFusesByRetrievalUnitAndKeepsDifferentUnitsFromOneNode() {
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(semantic.search(any())).thenReturn(List.of(candidate(101, 19, 23, 31, RetrievalChannel.SEMANTIC, .9, 1)));
        when(lexical.search(any())).thenReturn(List.of(
                candidate(101, 19, 23, 31, RetrievalChannel.LEXICAL, 10, 1),
                candidate(102, 19, 23, 31, RetrievalChannel.LEXICAL, 9, 2)));

        RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false)
                .inspect(new RetrievalV2Request(7, "question", 2));

        assertEquals(List.of(101L, 102L), stages.fusedCandidates().stream()
                .map(RetrievalCandidate::retrievalUnitId).toList());
        assertEquals(2, stages.finalCandidates().size());
        assertEquals(0, stages.degradedComponents().size());
    }

    @Test
    void activeBuildScopeOverflowDegradesLexicalWithoutTruncatingTheFilter() {
        IndexBuildRepository builds = activeBuilds(
                new ActiveBuildRef(23, 29, 31, "qwen3-v1"),
                new ActiveBuildRef(24, 30, 32, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(semantic.search(any())).thenReturn(List.of(candidate(101, 19, 23, 31, RetrievalChannel.SEMANTIC, .9, 1)));

        RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false)
                .inspect(new RetrievalV2Request(7, "question", 1));

        assertTrue(stages.degradedComponents().contains("active_build_scope"));
        assertTrue(stages.degradedComponents().contains("lexical"));
        assertEquals(1, stages.finalCandidates().size());
        verify(lexical, never()).search(any());
    }

    @Test
    void channelFailureDegradesIndependently() {
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(semantic.search(any())).thenThrow(new IllegalStateException("semantic down"));
        when(lexical.search(any())).thenReturn(List.of(candidate(102, 19, 23, 31, RetrievalChannel.LEXICAL, 5, 1)));

        RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false)
                .inspect(new RetrievalV2Request(7, "question", 1));

        assertTrue(stages.degradedComponents().contains("semantic"));
        assertFalse(stages.finalCandidates().isEmpty());
    }

    @Test
    void rerankerAdapterRejectsUnknownAndDuplicateUnitIdentities() {
        Reranker reranker = mock(Reranker.class);
        when(reranker.rerank(anyLong(), anyString(), any())).thenReturn(List.of(
                new com.modelrag.search.dto.ScoredChunk(999, "unknown", 10, "rerank", 1),
                new com.modelrag.search.dto.ScoredChunk(101, "one", 9, "rerank", 2),
                new com.modelrag.search.dto.ScoredChunk(101, "one", 8, "rerank", 3)));
        RetrievalCandidateReranker.RerankResult result = new RetrievalCandidateReranker(reranker).rerank(
                7, "question", List.of(
                        candidate(101, 19, 23, 31, RetrievalChannel.FUSED, .4, 1),
                        candidate(102, 19, 23, 31, RetrievalChannel.FUSED, .3, 2)));

        assertTrue(result.degraded());
        assertEquals(List.of(101L, 102L), result.candidates().stream()
                .map(RetrievalCandidate::retrievalUnitId).toList());
    }

    private HybridRetrievalService service(SemanticSearchPort semantic, LexicalSearchPort lexical,
            EmbeddingService embeddings, IndexBuildRepository builds, boolean rerank) {
        Reranker delegate = mock(Reranker.class);
        when(delegate.enabled()).thenReturn(rerank);
        return new HybridRetrievalService(semantic, lexical, embeddings, new QueryRewriter(), delegate,
                new ActiveBuildScopeResolver(builds, 1), .7, .3, 800, 500,
                new SimpleMeterRegistry(), Runnable::run, Runnable::run, Runnable::run);
    }

    private IndexBuildRepository activeBuilds(ActiveBuildRef... values) {
        IndexBuildRepository repository = mock(IndexBuildRepository.class);
        when(repository.findActiveByDataset(anyLong(), any(Integer.class))).thenReturn(List.of(values));
        return repository;
    }

    private RetrievalCandidate candidate(long unitId, long nodeId, long documentId, long buildId,
            RetrievalChannel channel, double score, int rank) {
        return new RetrievalCandidate(7, unitId, nodeId, documentId, 29, buildId, RetrievalUnitType.SECTION,
                "Title", "content " + unitId, score, channel, rank, Map.of());
    }
}
