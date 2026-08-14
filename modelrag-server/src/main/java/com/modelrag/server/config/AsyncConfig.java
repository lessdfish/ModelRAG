package com.modelrag.server.config;
import java.util.concurrent.*; import org.springframework.context.annotation.*;
@Configuration public class AsyncConfig {
 @Bean("indexingExecutor") public Executor indexingExecutor(){return executor("modelrag-index",2,4,200);}
 @Bean("answerExecutor") public Executor answerExecutor(){return executor("modelrag-answer",2,8,100);}
 private Executor executor(String prefix,int core,int max,int queue){
  ThreadPoolExecutor executor=new ThreadPoolExecutor(core,max,60,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queue),
    task->{Thread thread=new Thread(task);thread.setName(prefix+"-"+thread.getId());thread.setDaemon(true);return thread;},
    new ThreadPoolExecutor.AbortPolicy());
  executor.allowCoreThreadTimeOut(true);
  return executor;
 }
}
