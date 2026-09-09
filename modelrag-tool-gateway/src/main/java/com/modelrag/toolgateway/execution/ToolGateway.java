package com.modelrag.toolgateway.execution;

import com.modelrag.toolgateway.catalog.ToolCatalog;
import com.modelrag.toolgateway.catalog.ToolExecutionResolver;
import com.modelrag.toolgateway.catalog.ToolExecutionSpec;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.policy.ToolAccessPolicy;
import com.modelrag.toolgateway.trace.ToolCallTrace;
import com.modelrag.toolgateway.trace.ToolCallTracer;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Authoritative in-process tool execution facade. Approval remains in AgentRuntime. */
@Service
@Profile("!test")
public class ToolGateway implements ToolExecutionCanceller {
    private final ToolCatalog catalog;
    private final ToolExecutionResolver resolver;
    private final ToolAccessPolicy access;
    private final ResilientToolExecutor resilience;
    private final ToolDispatcher dispatcher;
    private final ToolCallTracer tracer;

    /**
     * Production constructor. The safe catalog lookup deliberately happens before the
     * credential-bearing execution spec is resolved.
     */
    @Autowired
    public ToolGateway(ToolCatalog catalog, ToolExecutionResolver resolver, ToolAccessPolicy access,
            ResilientToolExecutor resilience, ToolDispatcher dispatcher, ToolCallTracer tracer) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.resolver = resolver;
        this.access = access;
        this.resilience = resilience;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
    }

    /** Compatibility constructor for isolated tests with a resolver-only fake. */
    public ToolGateway(ToolExecutionResolver resolver, ToolAccessPolicy access,
            ResilientToolExecutor resilience, ToolDispatcher dispatcher, ToolCallTracer tracer) {
        this.catalog = resolver instanceof ToolCatalog value ? value : null;
        this.resolver = resolver;
        this.access = access;
        this.resilience = resilience;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
    }

    public ToolInvocationResult invoke(ToolInvocation invocation) {
        if (invocation == null || invocation.toolName() == null || invocation.toolName().isBlank()) {
            throw new IllegalArgumentException("工具调用不能为空");
        }
        long started = System.nanoTime();
        ToolDescriptor descriptor = null;
        try {
            if (catalog != null) {
                descriptor = catalog.get(invocation.toolName());
                access.requireAllowed(descriptor, invocation);
            }
            ToolExecutionSpec spec = resolver.resolveForExecution(invocation.toolName());
            if (spec == null || spec.descriptor() == null) {
                throw new IllegalStateException("工具执行配置不存在，已拒绝执行: " + invocation.toolName());
            }
            descriptor = spec.descriptor();
            if (!descriptor.enabled() || !descriptor.name().equals(invocation.toolName())) {
                throw new IllegalStateException("工具执行配置不匹配，已拒绝执行: " + invocation.toolName());
            }
            access.requireAllowed(descriptor, invocation);
            ResilientToolExecutor.Result<String> result = resilience.execute(descriptor, invocation.input(),
                    () -> dispatcher.dispatch(spec, invocation));
            long elapsed = elapsedMillis(started);
            boolean traced = record(invocation, descriptor, result.value(), true, null, elapsed);
            return new ToolInvocationResult(descriptor.name(), result.value(), result.attempts(), result.reused(),
                    elapsed, traced ? List.of() : List.of("tool-trace-unavailable"));
        } catch (RuntimeException error) {
            long elapsed = elapsedMillis(started);
            record(invocation, descriptor, "{}", false, error, elapsed);
            throw error;
        }
    }

    @Override
    public boolean cancel(Thread agentOwner) {
        Thread worker = resilience.activeWorker(agentOwner);
        if (worker == null) return false;
        boolean resourceCancelled = dispatcher.cancel(worker);
        boolean workerCancelled = resilience.cancel(agentOwner);
        return resourceCancelled || workerCancelled;
    }

    private boolean record(ToolInvocation invocation, ToolDescriptor descriptor, String output, boolean success,
            RuntimeException error, long latencyMs) {
        try {
            String toolName = descriptor == null ? invocation.toolName() : descriptor.name();
            String traceId = invocation.traceId().isBlank()
                    ? (invocation.actionId().isBlank() ? invocation.executionId() : invocation.actionId())
                    : invocation.traceId();
            tracer.record(new ToolCallTrace(traceId, toolName, invocation.input(), output, success,
                    error == null ? null : error.getMessage(), latencyMs));
            return true;
        } catch (RuntimeException ignored) {
            // Trace persistence is observability, not a reason to repeat a business call.
            return false;
        }
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
