package com.modelrag.search.reranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import com.modelrag.inference.rerank.RerankComputeProvider;
import com.modelrag.inference.rerank.RerankScore;
import com.modelrag.search.dto.ScoredChunk;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class HttpRerankerTest {
    @Test
    void mapsRemoteScoresByStablePositionAndHonorsCallerTimeout() {
        RerankComputeProvider compute = mock(RerankComputeProvider.class);
        AtomicReference<Duration> timeout = new AtomicReference<>();
        when(compute.rerank(any(), any(), any(), any())).thenAnswer(invocation -> {
            timeout.set(invocation.getArgument(3));
            return List.of(new RerankScore("1", 0.95), new RerankScore("0", 0.1));
        });
        HttpReranker reranker = reranker(compute, true, 1_000, null);

        List<ScoredChunk> result = reranker.rerank(7, "query", candidates(), Duration.ofMillis(80));

        assertEquals(List.of(2L, 1L), result.stream().map(ScoredChunk::chunkId).toList());
        assertEquals("rerank", result.get(0).channel());
        assertEquals(80, timeout.get().toMillis());
    }

    @Test
    void malformedRemoteScoresUseLocalDegradedPathAndMarkHealthFailure() {
        RerankComputeProvider compute = mock(RerankComputeProvider.class);
        when(compute.rerank(any(), any(), any(), any()))
                .thenReturn(List.of(new RerankScore("unknown", 0.9), new RerankScore("0", 0.1)));
        ModelHealthRegistry health = mock(ModelHealthRegistry.class);
        when(health.available("RERANK", "remote-reranker-cross-encoder-v1")).thenReturn(true);
        HttpReranker reranker = reranker(compute, true, 1_000, health);

        List<ScoredChunk> result = reranker.rerank(7, "query", candidates(), Duration.ofMillis(100));

        assertTrue(result.stream().allMatch(candidate -> "local-rerank".equals(candidate.channel())));
        verify(health).failure("RERANK", "remote-reranker-cross-encoder-v1");
    }

    @Test
    void disabledRemoteUsesLocalPathWithoutCallingCompute() {
        RerankComputeProvider compute = mock(RerankComputeProvider.class);
        List<ScoredChunk> result = reranker(compute, false, 1_000, null)
                .rerank(7, "query", candidates(), Duration.ofMillis(100));

        assertTrue(result.stream().allMatch(candidate -> "local-rerank".equals(candidate.channel())));
        org.mockito.Mockito.verifyNoInteractions(compute);
    }

    private HttpReranker reranker(RerankComputeProvider compute, boolean enabled, long timeoutMs,
            ModelHealthRegistry health) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ModelHealthRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(health);
        return new HttpReranker(enabled, "cross-encoder-v1", timeoutMs, compute,
                mock(TokenUsageTracker.class), provider);
    }

    private List<ScoredChunk> candidates() {
        return List.of(new ScoredChunk(1, "first document", 0.4, "bm25", 1),
                new ScoredChunk(2, "second document", 0.3, "vector", 2));
    }
}
