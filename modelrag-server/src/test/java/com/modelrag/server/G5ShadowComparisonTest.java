package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.IndexVersionRepository;
import com.modelrag.search.channel.Bm25Search;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import com.modelrag.search.orchestrator.SearchOrchestrator;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import com.modelrag.search.shadow.RetrievalShadowComparator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class G5ShadowComparisonTest {
    @Test
    void V1ReadPathStillUsesLegacyModelsWhenShadowIsDisabled() {
        var vectors = mock(com.modelrag.common.vector.VectorStore.class);
        var embeddings = mock(EmbeddingService.class);
        var bm25 = mock(Bm25Search.class);
        var reranker = mock(Reranker.class);
        var versions = mock(IndexVersionRepository.class);
        when(versions.findActiveByDatasetId(7)).thenReturn(Map.of(23L, 1L));
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(vectors.search(any())).thenReturn(List.of(new com.modelrag.common.vector.SearchResult(
                101, "legacy", .9, "vector")));
        when(bm25.search(any(), anyInt())).thenReturn(List.of(new ScoredChunk(102, "legacy lexical", .8, "bm25", 1)));

        SearchOrchestrator orchestrator = new SearchOrchestrator(vectors, embeddings, bm25, reranker,
                new QueryRewriter(), versions, .7, .3, 800, 500, new SimpleMeterRegistry(),
                Runnable::run, Runnable::run, Runnable::run);

        List<ScoredChunk> result = orchestrator.inspect(new HybridSearchRequest(7, "question", 2)).finalResults();

        verify(vectors, atLeastOnce()).search(any());
        verify(bm25, atLeastOnce()).search(any(), anyInt());
        assertEquals(List.of(101L, 102L), result.stream().map(ScoredChunk::chunkId).toList());
    }

    @Test
    void shadowFailureCannotChangeTheV1Result() {
        var vectors = mock(com.modelrag.common.vector.VectorStore.class);
        var embeddings = mock(EmbeddingService.class);
        var bm25 = mock(Bm25Search.class);
        var reranker = mock(Reranker.class);
        var versions = mock(IndexVersionRepository.class);
        var comparator = mock(RetrievalShadowComparator.class);
        @SuppressWarnings("unchecked") ObjectProvider<RetrievalShadowComparator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(comparator);
        when(comparator.compare(any(), any(), anyLong())).thenThrow(new IllegalStateException("shadow failure"));
        when(versions.findActiveByDatasetId(7)).thenReturn(Map.of());

        SearchOrchestrator orchestrator = new SearchOrchestrator(vectors, embeddings, bm25, reranker,
                new QueryRewriter(), versions, .7, .3, 800, 500, new SimpleMeterRegistry(),
                Runnable::run, Runnable::run, Runnable::run, Runnable::run, provider, true);

        assertDoesNotThrow(() -> orchestrator.inspect(new HybridSearchRequest(7, "question", 1)));
    }

    @Test
    void shadowComparesDocumentIdentityWithBoundedV1Lookup() {
        HybridRetrievalService v2 = mock(HybridRetrievalService.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        RetrievalCandidate candidate = new RetrievalCandidate(7, 201, 19, 23, 29, 31,
                RetrievalUnitType.SECTION, "Title", "content", .9, RetrievalChannel.FUSED, 1, Map.of());
        when(v2.inspect(any())).thenReturn(new RetrievalV2Stages("question", List.of("question"), "question",
                List.of(), List.of(), List.of(candidate), List.of(), false, List.of(candidate)));
        when(chunks.findActiveByIds(anyLong(), any())).thenReturn(List.of(
                new Chunk(101, 23, 7, 0, "legacy", Map.of())));

        RetrievalShadowComparator comparator = new RetrievalShadowComparator(v2, chunks, new SimpleMeterRegistry());
        var result = comparator.compare(new HybridSearchRequest(7, "question", 1),
                List.of(new ScoredChunk(101, "legacy", .8, "rrf", 1)), 4);

        assertEquals(1.0, result.documentOverlap());
        assertEquals(1, result.v2CandidateCount());
    }
}
