package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.common.vector.SearchResult;
import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.knowledge.model.ActiveBuildRef;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.search.channel.v2.ActiveBuildScopeResolver;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.channel.v2.LexicalSearchRequest;
import com.modelrag.search.channel.v2.SemanticSearchPort;
import com.modelrag.search.channel.v2.SemanticSearchTimeoutException;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class G5V2RetrievalTest {
    @Test
    void tenThousandAndOneActiveBuildsSelectTheOverflowPath() {
        IndexBuildRepository repository = mock(IndexBuildRepository.class);
        List<ActiveBuildRef> values = java.util.stream.LongStream.rangeClosed(1, 10_001)
                .mapToObj(id -> new ActiveBuildRef(id, id, id, "qwen3-v1")).toList();
        when(repository.findActiveByDataset(7, 10_001)).thenReturn(values);

        assertTrue(new ActiveBuildScopeResolver(repository, 10_000).resolve(7).overflow());
    }

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
    void activeBuildScopeOverflowStillRunsPostgresValidatedLexicalRecall() {
        IndexBuildRepository builds = activeBuilds(
                new ActiveBuildRef(23, 29, 31, "qwen3-v1"),
                new ActiveBuildRef(24, 30, 32, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(semantic.search(any())).thenReturn(List.of(candidate(101, 19, 23, 31, RetrievalChannel.SEMANTIC, .9, 1)));
        when(lexical.searchActiveValidatedResult(any())).thenReturn(new LexicalSearchPort.ActiveValidatedResult(List.of(
                candidate(102, 20, 24, 32, RetrievalChannel.LEXICAL, 5, 1)), false));

        RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false)
                .inspect(new RetrievalV2Request(7, "question", 1));

        assertFalse(stages.degradedComponents().contains("ACTIVE_BUILD_FILTER_LIMIT"));
        assertFalse(stages.degradedComponents().contains("ACTIVE_BUILD_VALIDATION_TRUNCATED"));
        assertEquals(List.of(102L), stages.lexicalCandidates().stream()
                .map(RetrievalCandidate::retrievalUnitId).toList());
        assertEquals(1, stages.finalCandidates().size());
        verify(lexical, never()).search(any());
        verify(lexical, org.mockito.Mockito.atLeastOnce()).searchActiveValidatedResult(any());
    }

    @Test
    void overflowOnlyDegradesWhenActiveValidationBudgetIsTruncated() {
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"),
                new ActiveBuildRef(24, 30, 32, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(lexical.searchActiveValidatedResult(any()))
                .thenReturn(new LexicalSearchPort.ActiveValidatedResult(List.of(), true));

        RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false)
                .inspect(new RetrievalV2Request(7, "question", 1), HybridRetrievalService.Mode.LEXICAL_ONLY);

        assertTrue(stages.degradedComponents().contains("ACTIVE_BUILD_VALIDATION_TRUNCATED"));
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

        assertTrue(stages.degradedComponents().contains("SEMANTIC_ERROR"));
        assertFalse(stages.finalCandidates().isEmpty());
    }

    @Test
    void complexQueryEmbedsOnlyTheSingleSemanticQuery() {
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(semantic.search(any())).thenReturn(List.of(
                candidate(101, 19, 23, 31, RetrievalChannel.SEMANTIC, .9, 1)));
        when(lexical.search(any())).thenReturn(List.of());

        RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false)
                .inspect(new RetrievalV2Request(7, "请问 2026 年假审批流程需要什么材料？", 1));

        assertTrue(stages.searchQueries().size() > 1);
        assertTrue(stages.performanceLatencyMs().containsKey("semantic.queueWaitMs"));
        assertTrue(stages.performanceLatencyMs().containsKey("semantic.embeddingMs"));
        assertTrue(stages.performanceLatencyMs().containsKey("semantic.vectorSearchMs"));
        assertTrue(stages.performanceLatencyMs().containsKey("semantic.executionMs"));
        assertTrue(stages.performanceLatencyMs().containsKey("semantic.channelMs"));
        assertTrue(stages.performanceLatencyMs().containsKey("semantic.requestObservedMs"));
        assertEquals("NOT_PRESENT", stages.performanceAttributes().get("semantic.embeddingCache"));
        verify(embeddings, times(1)).embed(7, "请问 2026 年假审批流程需要什么材料？");
    }

    @Test
    void lexicalQueryInternalRrfKeepsSentinelAcrossIncomparableRawScores() {
        String sentinel = "G11_ACTIVE_BUILD_SENTINEL_2000";
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        RetrievalCandidate sentinelCandidate = new RetrievalCandidate(7, 101, 19, 23, 29, 31,
                RetrievalUnitType.SECTION, "Benchmark", sentinel, .1, RetrievalChannel.LEXICAL, 1, Map.of());
        RetrievalCandidate distractor = candidate(102, 19, 23, 31, RetrievalChannel.LEXICAL, 100, 1);
        when(lexical.search(any())).thenAnswer(invocation -> {
            LexicalSearchRequest request = invocation.getArgument(0);
            if (sentinel.equals(request.query())) return List.of(sentinelCandidate);
            if (sentinel.toLowerCase().equals(request.query())) return List.of(distractor,
                    new RetrievalCandidate(7, 101, 19, 23, 29, 31, RetrievalUnitType.SECTION,
                            "Benchmark", sentinel, .05, RetrievalChannel.LEXICAL, 2, Map.of()));
            return List.of();
        });

        RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false)
                .inspect(new RetrievalV2Request(7, sentinel, 1), HybridRetrievalService.Mode.LEXICAL_ONLY);

        assertEquals(List.of(101L), stages.finalCandidates().stream()
                .map(RetrievalCandidate::retrievalUnitId).toList());
    }

    @Test
    void semanticDeadlineIsClassifiedAsTimeout() {
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenAnswer(invocation -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new float[1024];
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RetrievalV2Stages stages = service(semantic, lexical, embeddings, builds, false, 50, 1, executor)
                    .inspect(new RetrievalV2Request(7, "question", 1), HybridRetrievalService.Mode.SEMANTIC_ONLY);

            assertTrue(stages.degradedComponents().contains("SEMANTIC_TIMEOUT"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void semanticDatabaseTimeoutDoesNotLeakTheSingleWorker() {
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"));
        AtomicInteger calls = new AtomicInteger();
        SemanticSearchPort semantic = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new SemanticSearchTimeoutException("statement timeout");
            }
            return List.of(candidate(101, 19, 23, 31, RetrievalChannel.SEMANTIC, .9, 1));
        };
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            HybridRetrievalService service = service(semantic, lexical, embeddings, builds, false, 800, executor);
            RetrievalV2Stages timedOut = service.inspect(new RetrievalV2Request(7, "first", 1),
                    HybridRetrievalService.Mode.SEMANTIC_ONLY);
            RetrievalV2Stages recovered = service.inspect(new RetrievalV2Request(7, "second", 1),
                    HybridRetrievalService.Mode.SEMANTIC_ONLY);

            assertTrue(timedOut.degradedComponents().contains("SEMANTIC_TIMEOUT"));
            assertEquals(List.of(101L), recovered.finalCandidates().stream()
                    .map(RetrievalCandidate::retrievalUnitId).toList());
            assertEquals(2, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void benchmarkChannelModesExecuteOnlyTheRequestedRealChannel() {
        IndexBuildRepository builds = activeBuilds(new ActiveBuildRef(23, 29, 31, "qwen3-v1"));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        when(semantic.search(any())).thenReturn(List.of(
                candidate(101, 19, 23, 31, RetrievalChannel.SEMANTIC, .9, 1)));

        RetrievalV2Stages semanticStages = service(semantic, lexical, embeddings, builds, false).inspect(
                new RetrievalV2Request(7, "question", 1), HybridRetrievalService.Mode.SEMANTIC_ONLY);

        assertEquals(List.of(101L), semanticStages.finalCandidates().stream()
                .map(RetrievalCandidate::retrievalUnitId).toList());
        verify(semantic, org.mockito.Mockito.atLeastOnce()).search(any());
        verify(lexical, never()).search(any());
        verify(lexical, never()).searchActiveValidatedResult(any());
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
        return service(semantic, lexical, embeddings, builds, rerank, 800, Runnable::run);
    }

    private HybridRetrievalService service(SemanticSearchPort semantic, LexicalSearchPort lexical,
            EmbeddingService embeddings, IndexBuildRepository builds, boolean rerank, long timeout,
            Executor semanticExecutor) {
        return service(semantic, lexical, embeddings, builds, rerank, timeout, 300, semanticExecutor);
    }

    private HybridRetrievalService service(SemanticSearchPort semantic, LexicalSearchPort lexical,
            EmbeddingService embeddings, IndexBuildRepository builds, boolean rerank, long timeout,
            long semanticMinimumExecutionBudget, Executor semanticExecutor) {
        Reranker delegate = mock(Reranker.class);
        when(delegate.enabled()).thenReturn(rerank);
        return new HybridRetrievalService(semantic, lexical, embeddings, new QueryRewriter(), delegate,
                new ActiveBuildScopeResolver(builds, 1), .7, .3, timeout, semanticMinimumExecutionBudget, 125, 500,
                new SimpleMeterRegistry(), semanticExecutor, Runnable::run, Runnable::run);
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
