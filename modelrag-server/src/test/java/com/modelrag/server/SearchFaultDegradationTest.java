package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.modelrag.common.vector.SearchResult;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.knowledge.repository.IndexVersionRepository;
import com.modelrag.search.channel.Bm25Search;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.orchestrator.SearchOrchestrator;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SearchFaultDegradationTest {
    @Test
    void rrfKeepsBoundedSupportingEvidenceWhenNoRerankerIsConfigured() {
        VectorStore vectors = mock(VectorStore.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        Bm25Search bm25 = mock(Bm25Search.class);
        IndexVersionRepository versions = mock(IndexVersionRepository.class);
        Reranker reranker = mock(Reranker.class);
        when(versions.findActiveByDatasetId(7L)).thenReturn(Map.of(11L, 1L));
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(reranker.enabled()).thenReturn(false);
        when(vectors.search(any())).thenReturn(List.of(
                new SearchResult(101L, "first evidence", .9, "vector"),
                new SearchResult(102L, "supporting evidence", .8, "vector")));
        when(bm25.search(any(), anyInt())).thenReturn(List.of(
                new ScoredChunk(103L, "lexical evidence", 8, "bm25", 1)));

        var stages = orchestrator(vectors, embeddings, bm25, reranker, versions)
                .inspect(new HybridSearchRequest(7L, "policy", 3));

        assertTrue(stages.finalResults().size() > 1);
        assertTrue(stages.finalResults().size() <= 3);
    }

    @ParameterizedTest(name = "{0} outage retains the healthy retrieval channel")
    @MethodSource("channelFailures")
    void oneFailedRetrievalChannelReturnsObservableDegradedResults(
            String failedComponent, boolean vectorFails, boolean bm25Fails) {
        VectorStore vectors = mock(VectorStore.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        Bm25Search bm25 = mock(Bm25Search.class);
        IndexVersionRepository versions = mock(IndexVersionRepository.class);
        Reranker reranker = mock(Reranker.class);
        when(versions.findActiveByDatasetId(7L)).thenReturn(Map.of(11L, 1L));
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(reranker.enabled()).thenReturn(false);
        if (vectorFails) {
            when(vectors.search(any())).thenThrow(new IllegalStateException("pgvector unavailable"));
        } else {
            when(vectors.search(any())).thenReturn(List.of(
                    new SearchResult(101L, "vector evidence", .9, "vector")));
        }
        if (bm25Fails) {
            when(bm25.search(any(), anyInt()))
                    .thenThrow(new IllegalStateException("Elasticsearch unavailable"));
        } else {
            when(bm25.search(any(), anyInt())).thenReturn(List.of(
                    new ScoredChunk(102L, "lexical evidence", 8, "bm25", 1)));
        }

        var stages = orchestrator(vectors, embeddings, bm25, reranker, versions)
                .inspect(new HybridSearchRequest(7L, "policy", 3));

        assertTrue(stages.degradedComponents().contains(failedComponent));
        assertFalse(stages.finalResults().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("rerankerFallback")
    void localRerankerFallbackIsExposedAsDegradation(boolean localFallback) {
        VectorStore vectors = mock(VectorStore.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        Bm25Search bm25 = mock(Bm25Search.class);
        IndexVersionRepository versions = mock(IndexVersionRepository.class);
        Reranker reranker = mock(Reranker.class);
        when(versions.findActiveByDatasetId(7L)).thenReturn(Map.of(11L, 1L));
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(vectors.search(any())).thenReturn(List.of(new SearchResult(101L, "evidence one", .9, "vector")));
        when(bm25.search(any(), anyInt())).thenReturn(List.of(
                new ScoredChunk(102L, "evidence two", 8, "bm25", 1)));
        when(reranker.enabled()).thenReturn(true);
        when(reranker.rerank(anyLong(), anyString(), any())).thenAnswer(invocation -> {
            List<ScoredChunk> candidates = invocation.getArgument(2);
            String channel = localFallback ? "local-rerank" : "rerank";
            return candidates.stream().map(item -> new ScoredChunk(
                    item.chunkId(), item.content(), item.score(), channel, item.rank())).toList();
        });

        var stages = orchestrator(vectors, embeddings, bm25, reranker, versions)
                .inspect(new HybridSearchRequest(7L, "policy?", 3));

        assertTrue(stages.rerankApplied());
        assertTrue(stages.degradedComponents().contains("reranker") == localFallback);
        assertTrue(stages.latencyMs().containsKey("reranker"));
    }

    private SearchOrchestrator orchestrator(VectorStore vectors, EmbeddingService embeddings,
            Bm25Search bm25, Reranker reranker, IndexVersionRepository versions) {
        Executor direct = Runnable::run;
        return new SearchOrchestrator(vectors, embeddings, bm25, reranker, new QueryRewriter(), versions,
                .7, .3, 800, 500, new SimpleMeterRegistry(), direct, direct, direct);
    }

    private static Stream<Arguments> channelFailures() {
        return Stream.of(
                Arguments.of("pgvector", true, false),
                Arguments.of("elasticsearch", false, true));
    }

    private static Stream<Arguments> rerankerFallback() {
        return Stream.of(Arguments.of(true), Arguments.of(false));
    }
}
