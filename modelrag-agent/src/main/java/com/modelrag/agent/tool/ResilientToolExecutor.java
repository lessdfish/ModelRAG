package com.modelrag.agent.tool;

import java.util.concurrent.*; import java.util.function.Supplier; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;

@Service public class ResilientToolExecutor {
    public record Result<T>(T value,int attempts,boolean reused){}
    private record Circuit(String state,int failures,long openedUntil){}
    private record Window(long startedAt,int count){}
    private final ToolCallValidator validator; private final long timeoutMillis; private final int failureThreshold; private final long openMillis; private final int perMinuteLimit; private final ConcurrentHashMap<String,Object> completed=new ConcurrentHashMap<>(); private final ConcurrentHashMap<String,Circuit> circuits=new ConcurrentHashMap<>(); private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    public ResilientToolExecutor(ToolCallValidator validator){this(validator,30_000);}
    public ResilientToolExecutor(ToolCallValidator validator,long timeoutMillis){this(validator,timeoutMillis,3,30_000,60);}
    @Autowired public ResilientToolExecutor(ToolCallValidator validator,@Value("${modelrag.tools.timeout-ms:30000}") long timeoutMillis,@Value("${modelrag.tools.failure-threshold:3}") int failureThreshold,@Value("${modelrag.tools.open-ms:30000}") long openMillis,@Value("${modelrag.tools.per-minute-limit:60}") int perMinuteLimit){this.validator=validator;this.timeoutMillis=timeoutMillis;this.failureThreshold=Math.max(1,failureThreshold);this.openMillis=Math.max(0,openMillis);this.perMinuteLimit=perMinuteLimit;}
    @SuppressWarnings("unchecked") public <T> Result<T> execute(ToolDefinition tool,String params,Supplier<T> action){validator.validate(tool,params);String key=tool.name()+"\n"+params;Object existing=completed.get(key);if(existing!=null)return new Result<>((T)existing,0,true);ensureClosed(tool.name());rateLimit(tool.name());RuntimeException failure=null;for(int attempt=1;attempt<=2;attempt++)try{T value=call(action);recordSuccess(tool.name());completed.putIfAbsent(key,value);return new Result<>((T)completed.get(key),attempt,false);}catch(RuntimeException error){failure=error;}recordFailure(tool.name());throw failure;}
    public String circuitState(String toolName){return circuit(toolName).state();}
    private void ensureClosed(String toolName){Circuit current=circuit(toolName);if("OPEN".equals(current.state())&&System.currentTimeMillis()<current.openedUntil())throw new IllegalStateException("工具熔断中，请稍后重试");if("OPEN".equals(current.state()))circuits.put(toolName,new Circuit("HALF_OPEN",current.failures(),0));}
    private void rateLimit(String toolName){if(perMinuteLimit<=0)return;long now=System.currentTimeMillis();windows.compute(toolName,(name,old)->{Window current=old==null||now-old.startedAt()>=60_000?new Window(now,0):old;if(current.count()>=perMinuteLimit)throw new IllegalStateException("工具调用限流中，请稍后重试");return new Window(current.startedAt(),current.count()+1);});}
    private void recordSuccess(String toolName){circuits.put(toolName,new Circuit("CLOSED",0,0));}
    private void recordFailure(String toolName){Circuit old=circuit(toolName);int failures=old.failures()+1;circuits.put(toolName,"HALF_OPEN".equals(old.state())||failures>=failureThreshold?new Circuit("OPEN",failures,System.currentTimeMillis()+openMillis):new Circuit("CLOSED",failures,0));}
    private Circuit circuit(String toolName){return circuits.getOrDefault(toolName,new Circuit("CLOSED",0,0));}
    private <T> T call(Supplier<T> action){Future<T> future=CompletableFuture.supplyAsync(action);try{return future.get(timeoutMillis,TimeUnit.MILLISECONDS);}catch(TimeoutException e){future.cancel(true);throw new IllegalStateException("工具调用超时");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("工具调用被中断",e);}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof RuntimeException error)throw error;throw new IllegalStateException(cause);} }
}
