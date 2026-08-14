package com.modelrag.indexing.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dedicated bounded bulkhead for provider embedding requests. */
@Configuration
public class EmbeddingExecutorConfig {
    @Bean("embeddingExecutor")
    public ExecutorService embeddingExecutor() {
        return new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), task -> {
                    Thread thread = new Thread(task, "modelrag-embedding-" + System.nanoTime());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
