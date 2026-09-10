package com.modelrag.indexing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.common.cache.EmbeddingCache;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import com.modelrag.inference.embedding.EmbeddingComputeProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EmbeddingServiceTest {
    @Test
    void cacheHitDoesNotCallComputeProvider() {
        EmbeddingComputeProvider provider = mock(EmbeddingComputeProvider.class);
        when(provider.embed(anyString(), eq(1024), anyList(), any(Duration.class)))
                .thenReturn(List.of(vector(1)));
        EmbeddingService service = service(provider, new EmbeddingCache());

        float[] first = service.embed(7, "相同文本");
        float[] second = service.embed(7, "相同文本");

        assertEquals(1024, first.length);
        assertEquals(first, second);
        verify(provider, times(1)).embed(anyString(), eq(1024), anyList(), any(Duration.class));
    }

    @Test
    void duplicateInputsComputeOnceAndBatchesPreserveOrder() {
        EmbeddingComputeProvider provider = mock(EmbeddingComputeProvider.class);
        when(provider.embed(anyString(), eq(1024), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(2);
            return texts.stream().map(text -> vector(text.hashCode())).toList();
        });
        EmbeddingService service = service(provider, new EmbeddingCache());
        List<String> texts = new ArrayList<>();
        texts.add("duplicate");
        for (int index = 0; index < 32; index++) texts.add("text-" + index);
        texts.add("duplicate");

        List<float[]> result = service.embedBatch(3, texts);

        assertEquals(texts.size(), result.size());
        assertEquals(result.get(0), result.get(result.size() - 1));
        verify(provider, times(2)).embed(anyString(), eq(1024), anyList(), any(Duration.class));
    }

    @Test
    void malformedProviderOutputFailsClosedWithoutAlternateModel() {
        EmbeddingComputeProvider provider = mock(EmbeddingComputeProvider.class);
        when(provider.embed(anyString(), anyInt(), anyList(), any(Duration.class)))
                .thenReturn(List.of(new float[3]));
        EmbeddingService service = service(provider, new EmbeddingCache());

        assertThrows(IllegalStateException.class, () -> service.embed(1, "bad"));
        verify(provider, times(1)).embed(anyString(), eq(1024), anyList(), any(Duration.class));
    }

    @Test
    void timeoutPassedToProviderIsBoundedByServiceConfiguration() {
        EmbeddingComputeProvider provider = mock(EmbeddingComputeProvider.class);
        AtomicReference<Duration> timeout = new AtomicReference<>();
        when(provider.embed(anyString(), eq(1024), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            timeout.set(invocation.getArgument(3));
            return List.of(vector(4));
        });
        EmbeddingService service = new EmbeddingService(new EmbeddingCache(), provider,
                "Qwen3-Embedding-0.6B", 250, mock(TokenUsageTracker.class), health());

        service.embed(1, "deadline");

        assertEquals(250, timeout.get().toMillis());
    }

    private EmbeddingService service(EmbeddingComputeProvider provider, EmbeddingCache cache) {
        return new EmbeddingService(cache, provider, "Qwen3-Embedding-0.6B", 1_000,
                mock(TokenUsageTracker.class), health());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ModelHealthRegistry> health() {
        ObjectProvider<ModelHealthRegistry> health = mock(ObjectProvider.class);
        when(health.getIfAvailable()).thenReturn(null);
        return health;
    }

    private static float[] vector(int seed) {
        float[] result = new float[1024];
        result[Math.floorMod(seed, result.length)] = 1;
        return result;
    }
}
