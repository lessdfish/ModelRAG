package com.modelrag.agent.tool;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Bounded tool execution. Coordination state is delegated to Redis in production. */
@Service
@Profile("!test")
public class ResilientToolExecutor {
    public record Result<T>(T value, int attempts, boolean reused) { }
    private record Circuit(String state, int failures, long openedUntil) { }

    private final ToolCallValidator validator;
    private final ToolCoordinationStore coordination;
    private final long timeoutMillis;
    private final int failureThreshold;
    private final long openMillis;
    private final int perMinuteLimit;
    private final ConcurrentHashMap<Thread, Thread> activeWorkers = new ConcurrentHashMap<>();

    /** Explicit constructor used by the test-profile replacement with a test coordination store. */
    public ResilientToolExecutor(ToolCallValidator validator, ToolCoordinationStore coordination) {
        this(validator, coordination, 30_000, 3, 30_000, 60, true);
    }

    @Autowired
    public ResilientToolExecutor(ToolCallValidator validator, ToolCoordinationStore coordination,
            @Value("${modelrag.tools.timeout-ms:30000}") long timeoutMillis,
            @Value("${modelrag.tools.failure-threshold:3}") int failureThreshold,
            @Value("${modelrag.tools.open-ms:30000}") long openMillis,
            @Value("${modelrag.tools.per-minute-limit:60}") int perMinuteLimit) {
        this(validator, coordination, timeoutMillis, failureThreshold, openMillis, perMinuteLimit, true);
    }

    private ResilientToolExecutor(ToolCallValidator validator, ToolCoordinationStore coordination,
            long timeoutMillis, int failureThreshold, long openMillis, int perMinuteLimit, boolean initialized) {
        this.validator = validator;
        this.coordination = coordination;
        this.timeoutMillis = Math.max(100, timeoutMillis);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(0, openMillis);
        this.perMinuteLimit = Math.max(0, perMinuteLimit);
    }

    public <T> Result<T> execute(ToolDefinition tool, String params, Supplier<T> action) {
        validator.validate(tool, params);
        coordination.ensureAvailable();
        String name = tool == null ? "unknown" : tool.name();
        ensureClosed(name);
        rateLimit(name);
        RuntimeException failure = null;
        int attempts = tool != null && (readOnly(tool) || tool.idempotent()) ? 2 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                T value = call(action);
                recordSuccess(name);
                return new Result<>(value, attempt, false);
            } catch (RuntimeException error) {
                failure = error;
                if (attempt >= attempts || !retryable(error)) break;
            }
        }
        recordFailure(name);
        throw failure;
    }

    public String circuitState(String toolName) {
        coordination.ensureAvailable();
        return circuit(toolName).state();
    }

    /** Interrupts the currently executing tool worker owned by an Agent thread. */
    public boolean cancel(Thread owner) {
        Thread worker = activeWorker(owner);
        if (worker == null) return false;
        worker.interrupt();
        return true;
    }

    public Thread activeWorker(Thread owner) {
        return owner == null ? null : activeWorkers.get(owner);
    }

    private void ensureClosed(String toolName) {
        Circuit current = circuit(toolName);
        if ("OPEN".equals(current.state()) && System.currentTimeMillis() < current.openedUntil()) {
            throw new IllegalStateException("工具熔断中，请稍后重试");
        }
        if ("OPEN".equals(current.state())) saveCircuit(toolName, new Circuit("HALF_OPEN", current.failures(), 0));
    }

    private void rateLimit(String toolName) {
        if (perMinuteLimit <= 0) return;
        if (coordination.incrementRate(toolName, Duration.ofMinutes(1)) > perMinuteLimit) {
            throw new IllegalStateException("工具调用限流中，请稍后重试");
        }
    }

    private void recordSuccess(String toolName) {
        saveCircuit(toolName, new Circuit("CLOSED", 0, 0));
    }

    private void recordFailure(String toolName) {
        Circuit old = circuit(toolName);
        int failures = old.failures() + 1;
        Circuit next = "HALF_OPEN".equals(old.state()) || failures >= failureThreshold
                ? new Circuit("OPEN", failures, System.currentTimeMillis() + openMillis)
                : new Circuit("CLOSED", failures, 0);
        saveCircuit(toolName, next);
    }

    private Circuit circuit(String toolName) {
        String value = coordination.circuit(toolName);
        if (value == null || value.isBlank()) return new Circuit("CLOSED", 0, 0);
        String[] fields = value.split("\\|", -1);
        try {
            return new Circuit(fields[0], Integer.parseInt(fields[1]), Long.parseLong(fields[2]));
        } catch (RuntimeException error) {
            throw new IllegalStateException("工具熔断状态损坏，已拒绝执行", error);
        }
    }

    private void saveCircuit(String toolName, Circuit circuit) {
        coordination.saveCircuit(toolName, circuit.state(), circuit.failures(), circuit.openedUntil(),
                Duration.ofMinutes(10));
    }

    private <T> T call(Supplier<T> action) {
        FutureTask<T> future = new FutureTask<>(action::get);
        Thread worker = Thread.ofVirtual().name("modelrag-tool-call").start(future);
        Thread owner = Thread.currentThread();
        activeWorkers.put(owner, worker);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            worker.interrupt();
            throw new IllegalStateException("工具调用超时");
        } catch (InterruptedException error) {
            future.cancel(true);
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工具调用被中断", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } finally {
            activeWorkers.remove(owner, worker);
        }
    }

    private boolean readOnly(ToolDefinition tool) {
        return tool != null && "LOW".equalsIgnoreCase(tool.riskLevel()) && !tool.http();
    }

    private boolean retryable(RuntimeException error) {
        String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("timeout") || message.contains("超时") || message.contains("connection")
                || message.contains("连接") || message.matches(".*状态码 (408|425|429|5[0-9]{2}).*");
    }
}
