package com.modelrag.server.api;

import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.api.ToolInvoker;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.toolgateway.catalog.ToolCatalog;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.execution.ToolGateway;
import com.modelrag.toolgateway.execution.ToolInvocation;
import com.modelrag.toolgateway.policy.ToolAccessPolicy;
import com.modelrag.toolgateway.policy.ToolRiskPolicy;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Authenticated adapter for the public tool SPI; side effects stop at the persisted approval gate. */
@Service
@Profile("!test")
public class DefaultToolInvoker implements ToolInvoker {
    private final ToolCatalog registry;
    private final ToolRiskPolicy risk;
    private final ToolAccessPolicy toolAccess;
    private final ToolGateway gateway;
    private final ApprovalGate approvals;
    private final AccessControlService access;

    public DefaultToolInvoker(ToolCatalog registry, ToolRiskPolicy risk, ToolAccessPolicy toolAccess,
            ToolGateway gateway, ApprovalGate approvals, AccessControlService access) {
        this.registry = registry;
        this.risk = risk;
        this.toolAccess = toolAccess;
        this.gateway = gateway;
        this.approvals = approvals;
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
        ToolDescriptor registered = registry.get(requested.name());
        requireMatchingContract(requested, registered);
        if (!toolAccess.allowed(registered, current.id(), current.roles(), datasetId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前用户无权调用该工具");
        }
        String executionId = UUID.randomUUID().toString();
        if (risk.requiresApproval(registered)) {
            var approval = approvals.request(executionId, registered.name(), parameters, current.id(), datasetId, null);
            return new ToolResult("WAITING_APPROVAL", "approvalId=" + approval.id(), executionId, true);
        }
        if (!registered.http()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "内置知识库工具必须通过 Agent 调用，以保留会话与权限上下文");
        }
        String stableKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? executionId + ":" + registered.name() : idempotencyKey;
        var result = gateway.invoke(new ToolInvocation(executionId, executionId + ":action:1", registered.name(),
                current.id(), current.roles(), datasetId, null, parameters, stableKey, executionId));
        return new ToolResult("DONE", limit(result.output()), executionId, false);
    }

    private void requireMatchingContract(com.modelrag.api.ToolDefinition requested, ToolDescriptor registered) {
        String risk = switch (requested.risk()) {
            case READ_ONLY -> "LOW";
            case WRITE -> "HIGH";
            case EXTERNAL_SIDE_EFFECT -> "EXTERNAL_SIDE_EFFECT";
        };
        if (!risk.equalsIgnoreCase(registered.riskLevel()) || requested.idempotent() != registered.idempotent()) {
            throw new BusinessException(ErrorCode.VALIDATION, "调用方工具契约与已注册工具不一致");
        }
    }

    private String limit(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
