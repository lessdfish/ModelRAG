package com.modelrag.search.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps retrieval work isolated from request, answer, indexing, and embedding pools. */
@Configuration
public class SearchExecutorConfig {

    @Bean("vectorSearchExecutor")
    public Executor vectorSearchExecutor() {
        return executor("modelrag-vector-search-");
    }

    @Bean("bm25SearchExecutor")
    public Executor bm25SearchExecutor() {
        return executor("modelrag-bm25-search-");
    }

    @Bean("rerankExecutor")
    public Executor rerankExecutor() {
        return executor("modelrag-rerank-");
    }

    private Executor executor(String prefix) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                2,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
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
