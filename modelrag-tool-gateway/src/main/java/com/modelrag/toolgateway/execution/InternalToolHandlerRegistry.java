package com.modelrag.toolgateway.execution;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Dispatches only to explicitly registered INTERNAL handlers and fails closed otherwise. */
@Service
@Profile("!test")
public class InternalToolHandlerRegistry {
    private final List<InternalToolHandler> handlers;

    public InternalToolHandlerRegistry(List<InternalToolHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public String invoke(ToolInvocation invocation) {
        if (invocation == null || invocation.toolName() == null || invocation.toolName().isBlank()) {
            throw new IllegalArgumentException("内部工具名称不能为空");
        }
        return handlers.stream()
                .filter(handler -> handler.supports(invocation.toolName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未知 INTERNAL 工具，已拒绝执行: " + invocation.toolName()))
                .invoke(invocation);
    }
}
