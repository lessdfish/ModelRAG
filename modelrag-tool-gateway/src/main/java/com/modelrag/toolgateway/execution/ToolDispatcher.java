package com.modelrag.toolgateway.execution;

import com.modelrag.toolgateway.catalog.ToolExecutionSpec;
import com.modelrag.toolgateway.http.HttpToolInvoker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Selects a concrete gateway-owned adapter for a resolved tool. */
@Service
@Profile("!test")
public class ToolDispatcher {
    private final HttpToolInvoker http;
    private final InternalToolHandlerRegistry internal;

    public ToolDispatcher(HttpToolInvoker http, InternalToolHandlerRegistry internal) {
        this.http = http;
        this.internal = internal;
    }

    public String dispatch(ToolExecutionSpec spec, ToolInvocation invocation) {
        if (spec == null || invocation == null) throw new IllegalArgumentException("工具执行上下文不能为空");
        if (spec.descriptor().http()) {
            return http.invoke(spec, invocation.input(), invocation.idempotencyKey());
        }
        if ("INTERNAL".equalsIgnoreCase(spec.descriptor().type())) {
            return internal.invoke(invocation);
        }
        throw new IllegalStateException("未知工具类型，已拒绝执行: " + spec.descriptor().type());
    }

    /** Cancels gateway-owned HTTP resources for a worker before interrupting it. */
    public boolean cancel(Thread worker) {
        return http.cancel(worker);
    }
}
