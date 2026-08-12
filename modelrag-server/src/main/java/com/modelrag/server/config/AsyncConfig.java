package com.modelrag.server.config;
import java.util.concurrent.*; import org.springframework.context.annotation.*;
@Configuration public class AsyncConfig {@Bean("indexingExecutor")public Executor indexingExecutor(){int cores=Math.max(1,Runtime.getRuntime().availableProcessors());return new ThreadPoolExecutor(cores,cores*2,60,TimeUnit.SECONDS,new ArrayBlockingQueue<>(500),new ThreadPoolExecutor.CallerRunsPolicy());}}
