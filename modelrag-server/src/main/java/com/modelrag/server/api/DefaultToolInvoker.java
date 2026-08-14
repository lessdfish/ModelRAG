package com.modelrag.server.api;

import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.tool.HttpToolInvoker;
import com.modelrag.agent.tool.ResilientToolExecutor;
import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.agent.tool.ToolRegistry;
import com.modelrag.agent.trace.ToolCallTrace;
import com.modelrag.agent.trace.ToolCallTracer;
import com.modelrag.api.ToolInvoker;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Authenticated adapter for the public tool SPI; side effects stop at the persisted approval gate. */
@Service
@Profile("!test")
public class DefaultToolInvoker implements ToolInvoker {
    private final ToolRegistry registry;
    private final ResilientToolExecutor executor;
    private final HttpToolInvoker http;
    private final ApprovalGate approvals;
    private final ToolCallTracer traces;
    private final AccessControlService access;

    public DefaultToolInvoker(ToolRegistry registry, ResilientToolExecutor executor, HttpToolInvoker http,
            ApprovalGate approvals, ToolCallTracer traces, AccessControlService access) {
        this.registry = registry;
        this.executor = executor;
        this.http = http;
        this.approvals = approvals;
        this.traces = traces;
        this.access = access;
    }

    @Override
    public ToolResult invoke(com.modelrag.api.ToolDefinition requested, String userId, long datasetId,
            String idempotencyKey, String parameters) {
        if (requested == null) throw new BusinessException(ErrorCode.VALIDATION, "工具定义不能为空");
        var current = access.currentUser();
        if (userId != null && !userId.isBlank() && !current.id().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能代表其他用户调用工具");
        }
        if (!current.canAccess(datasetId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户无权访问知识库: " + datasetId);
        }
        ToolDefinition registered = registry.get(requested.name());
        requireMatchingContract(requested, registered);
        requireToolAccess(registered, current.roles(), datasetId);
        String executionId = UUID.randomUUID().toString();
        if (!"LOW".equalsIgnoreCase(registered.riskLevel())) {
            var approval = approvals.request(executionId, registered.name(), parameters, current.id(), datasetId, null);
            return new ToolResult("WAITING_APPROVAL", "approvalId=" + approval.id(), executionId, true);
        }
        if (!registered.http()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "内置知识库工具必须通过 Agent 调用，以保留会话与权限上下文");
        }
        String stableKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? executionId + ":" + registered.name() : idempotencyKey;
        long started = System.nanoTime();
        try {
            var result = executor.execute(registered, parameters,
                    () -> http.invoke(registered, parameters, stableKey));
            traces.record(new ToolCallTrace(executionId, registered.name(), parameters,
                    "{\"status\":\"DONE\",\"attempts\":" + result.attempts() + "}", true, null,
                    (System.nanoTime() - started) / 1_000_000));
            return new ToolResult("DONE", limit(result.value()), executionId, false);
        } catch (RuntimeException error) {
            traces.record(new ToolCallTrace(executionId, registered.name(), parameters, "{}", false,
                    error.getMessage(), (System.nanoTime() - started) / 1_000_000));
            throw error;
        }
    }

    private void requireMatchingContract(com.modelrag.api.ToolDefinition requested, ToolDefinition registered) {
        String risk = switch (requested.risk()) {
            case READ_ONLY -> "LOW";
            case WRITE -> "HIGH";
            case EXTERNAL_SIDE_EFFECT -> "EXTERNAL_SIDE_EFFECT";
        };
        if (!risk.equalsIgnoreCase(registered.riskLevel()) || requested.idempotent() != registered.idempotent()) {
            throw new BusinessException(ErrorCode.VALIDATION, "调用方工具契约与已注册工具不一致");
        }
    }

    private void requireToolAccess(ToolDefinition tool, java.util.Set<String> roles, long datasetId) {
        if (tool.allowedRoles() != null && !tool.allowedRoles().isEmpty()
                && tool.allowedRoles().stream().noneMatch(roles::contains)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前用户角色无权调用该工具");
        }
        if (tool.allowedDatasetIds() != null && !tool.allowedDatasetIds().isEmpty()
                && !tool.allowedDatasetIds().contains(datasetId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "工具未授权给该知识库");
        }
    }

    private String limit(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
