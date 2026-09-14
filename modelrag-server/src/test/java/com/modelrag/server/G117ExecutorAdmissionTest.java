package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.knowledge.model.ActiveBuildRef;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.search.channel.v2.ActiveBuildScopeResolver;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.channel.v2.SemanticSearchPort;
import com.modelrag.search.config.SearchExecutorConfig;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class G117ExecutorAdmissionTest {

    @Test
    void semanticAdmissionTimeoutSkipsEmbeddingAndPostgres() throws Exception {
        ThreadPoolExecutor executor = executor(1, 2);
        CountDownLatch release = occupy(executor);
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        CompletableFuture.runAsync(() -> releaseAfter(release, 100));
        try {
            RetrievalV2Stages stages = service(semantic, lexical, embeddings, 200, 150, 50,
                    executor, Runnable::run).inspect(request(), HybridRetrievalService.Mode.SEMANTIC_ONLY);

            assertTrue(stages.degradedComponents().contains("SEMANTIC_ADMISSION_TIMEOUT"));
            verify(embeddings, never()).embed(anyLong(), anyString());
            verify(semantic, never()).search(any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lexicalAdmissionTimeoutSkipsElasticsearch() throws Exception {
        ThreadPoolExecutor executor = executor(1, 2);
        CountDownLatch release = occupy(executor);
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        CompletableFuture.runAsync(() -> releaseAfter(release, 100));
        try {
            RetrievalV2Stages stages = service(semantic, lexical, embeddings, 200, 50, 150,
                    Runnable::run, executor).inspect(request(), HybridRetrievalService.Mode.LEXICAL_ONLY);

            assertTrue(stages.degradedComponents().contains("LEXICAL_ADMISSION_TIMEOUT"));
            verify(lexical, never()).search(any());
            verify(lexical, never()).searchActiveValidatedResult(any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void fullBoundedQueuesRejectWithoutRunningOnCaller() throws Exception {
        assertRejected("SEMANTIC");
        assertRejected("LEXICAL");
    }

    @Test
    void performanceMetricsHaveUnambiguousChannelAndExecutionSemantics() {
        SemanticSearchPort semantic = request -> List.of();
        LexicalSearchPort lexical = request -> List.of();
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyLong(), anyString())).thenReturn(new float[1024]);
        HybridRetrievalService service = service(semantic, lexical, embeddings, 800, 1, 1,
                Runnable::run, Runnable::run);

        RetrievalV2Stages semanticStages = service.inspect(request(), HybridRetrievalService.Mode.SEMANTIC_ONLY);
        double semanticQueue = metric(semanticStages, "semantic.queueWaitMs");
        double semanticEmbedding = metric(semanticStages, "semantic.embeddingMs");
        double semanticVector = metric(semanticStages, "semantic.vectorSearchMs");
        double semanticExecution = metric(semanticStages, "semantic.executionMs");
        double semanticChannel = metric(semanticStages, "semantic.channelMs");
        assertTrue(semanticChannel >= semanticQueue);
        assertTrue(semanticChannel >= semanticExecution);
        assertTrue(semanticExecution >= semanticEmbedding);
        assertTrue(semanticExecution >= semanticVector);

        RetrievalV2Stages lexicalStages = service.inspect(request(), HybridRetrievalService.Mode.LEXICAL_ONLY);
        double lexicalQueue = metric(lexicalStages, "lexical.queueWaitMs");
        double lexicalExecution = metric(lexicalStages, "lexical.executionMs");
        double lexicalChannel = metric(lexicalStages, "lexical.channelMs");
        assertTrue(lexicalChannel >= lexicalQueue);
        assertTrue(lexicalChannel >= lexicalExecution);
    }

    @Test
    void configuredRetrievalPoolIsFixedBoundedAndUsesAbortPolicy() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) new SearchExecutorConfig().vectorSearchExecutor(2, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> await(release));
            executor.execute(() -> await(release));
            waitForActive(executor, 2);
            executor.execute(() -> { });

            assertEquals(2, executor.getCorePoolSize());
            assertEquals(2, executor.getMaximumPoolSize());
            assertInstanceOf(ArrayBlockingQueue.class, executor.getQueue());
            assertEquals(1, executor.getQueue().size());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, executor.getRejectedExecutionHandler());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> executor.execute(() -> { throw new AssertionError("must not run on caller"); }));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void assertRejected(String channel) throws Exception {
        ThreadPoolExecutor executor = executor(1, 1);
        CountDownLatch release = occupy(executor);
        executor.execute(() -> await(release));
        SemanticSearchPort semantic = mock(SemanticSearchPort.class);
        LexicalSearchPort lexical = mock(LexicalSearchPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        try {
            HybridRetrievalService service = service(semantic, lexical, embeddings, 800, 300, 125,
                    "SEMANTIC".equals(channel) ? executor : Runnable::run,
                    "LEXICAL".equals(channel) ? executor : Runnable::run);
            RetrievalV2Stages stages = service.inspect(request(), "SEMANTIC".equals(channel)
                    ? HybridRetrievalService.Mode.SEMANTIC_ONLY : HybridRetrievalService.Mode.LEXICAL_ONLY);

            assertTrue(stages.degradedComponents().contains(channel + "_REJECTED"));
            assertTrue(stages.degradedComponents().stream().noneMatch(value -> value.endsWith("_TIMEOUT")));
            verify(embeddings, never()).embed(anyLong(), anyString());
            verify(semantic, never()).search(any());
            verify(lexical, never()).search(any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private HybridRetrievalService service(SemanticSearchPort semantic, LexicalSearchPort lexical,
            EmbeddingService embeddings, long timeout, long semanticMinimum, long lexicalMinimum,
            Executor semanticExecutor, Executor lexicalExecutor) {
        IndexBuildRepository builds = mock(IndexBuildRepository.class);
        when(builds.findActiveByDataset(anyLong(), any(Integer.class)))
                .thenReturn(List.of(new ActiveBuildRef(1, 1, 1, "qwen3-v1")));
        Reranker reranker = mock(Reranker.class);
        when(reranker.enabled()).thenReturn(false);
        return new HybridRetrievalService(semantic, lexical, embeddings, new QueryRewriter(), reranker,
                new ActiveBuildScopeResolver(builds, 10_000), .7, .3, timeout,
                semanticMinimum, lexicalMinimum, 500, new SimpleMeterRegistry(),
                semanticExecutor, lexicalExecutor, Runnable::run);
    }

    private RetrievalV2Request request() {
        return new RetrievalV2Request(7, "capacity question", 1);
    }

    private double metric(RetrievalV2Stages stages, String name) {
        assertTrue(stages.performanceLatencyMs().containsKey(name), "missing " + name);
        return stages.performanceLatencyMs().get(name);
    }

    private ThreadPoolExecutor executor(int workers, int queue) {
        return new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), new ThreadPoolExecutor.AbortPolicy());
    }

    private CountDownLatch occupy(ThreadPoolExecutor executor) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> await(release));
        waitForActive(executor, 1);
        return release;
    }

    private void waitForActive(ThreadPoolExecutor executor, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.getActiveCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, executor.getActiveCount());
    }

    private static void releaseAfter(CountDownLatch latch, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        latch.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
