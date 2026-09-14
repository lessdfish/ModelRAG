package com.modelrag.search.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps retrieval work isolated from request, answer, indexing, and embedding pools. */
@Configuration
public class SearchExecutorConfig {

    @Bean("vectorSearchExecutor")
    public Executor vectorSearchExecutor(
            @Value("${modelrag.search.semantic-workers:8}") int workers,
            @Value("${modelrag.search.semantic-queue-capacity:32}") int queueCapacity) {
        return fixedExecutor("modelrag-vector-search-", workers, queueCapacity);
    }

    @Bean("bm25SearchExecutor")
    public Executor bm25SearchExecutor(
            @Value("${modelrag.search.lexical-workers:8}") int workers,
            @Value("${modelrag.search.lexical-queue-capacity:32}") int queueCapacity) {
        return fixedExecutor("modelrag-bm25-search-", workers, queueCapacity);
    }

    @Bean("rerankExecutor")
    public Executor rerankExecutor() {
        return elasticExecutor("modelrag-rerank-");
    }

    @Bean("retrievalShadowExecutor")
    public Executor retrievalShadowExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                task -> {
                    Thread thread = new Thread(task);
                    thread.setName("modelrag-retrieval-shadow-" + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private Executor fixedExecutor(String prefix, int workers, int queueCapacity) {
        int boundedWorkers = Math.max(1, Math.min(16, workers));
        return executor(prefix, boundedWorkers, boundedWorkers, queueCapacity);
    }

    private Executor elasticExecutor(String prefix) {
        return executor(prefix, 1, 2, 32);
    }

    private Executor executor(String prefix, int core, int max, int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                core,
                max,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, Math.min(256, queueCapacity))),
                task -> {
                    Thread thread = new Thread(task);
                    thread.setName(prefix + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
