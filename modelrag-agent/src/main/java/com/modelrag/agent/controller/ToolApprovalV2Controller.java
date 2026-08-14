package com.modelrag.agent.controller;

import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.approval.ApprovalSnapshot;
import com.modelrag.agent.orchestrator.AgentOrchestrator;
import com.modelrag.agent.orchestrator.AgentResult;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.agent.tool.ToolRegistry;
import com.modelrag.agent.trace.ToolCallTrace;
import com.modelrag.agent.trace.ToolCallTracer;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.RequestUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class ToolApprovalV2Controller {
    private final ToolRegistry tools;
    private final ApprovalGate approvals;
    private final AccessControlService access;
    private final AgentOrchestrator agent;
    private final ToolCallTracer traces;

    public ToolApprovalV2Controller(ToolRegistry tools, ApprovalGate approvals, AccessControlService access,
            AgentOrchestrator agent, ToolCallTracer traces) {
        this.tools = tools;
        this.approvals = approvals;
        this.access = access;
        this.agent = agent;
        this.traces = traces;
    }

    @GetMapping("/tools")
    public ApiResponse<List<ToolView>> listTools(
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        RequestUser user = access.currentUser();
        if (includeDisabled) {
            access.requireRole("ADMIN");
            return ApiResponse.success(tools.list().stream().map(this::view).toList());
        }
        return ApiResponse.success(tools.listEnabled().stream()
                .filter(tool -> allowed(tool, user))
                .map(this::view).toList());
    }

    @PostMapping("/tools")
    public ApiResponse<ToolView> createTool(@Valid @RequestBody ToolRequest request) {
        access.requireRole("ADMIN");
        return ApiResponse.success(view(tools.register(request.definition())));
    }

    @PutMapping("/tools/{toolId}")
    public ApiResponse<ToolView> updateTool(@PathVariable String toolId, @Valid @RequestBody ToolRequest request) {
        access.requireRole("ADMIN");
        ToolDefinition existing = tools.getAny(toolId);
        return ApiResponse.success(view(tools.register(request.definition(toolId,
                request.authHeaderValue() == null || request.authHeaderValue().isBlank()
                        ? existing.authHeaderValue() : request.authHeaderValue()))));
    }

    @DeleteMapping("/tools/{toolId}")
    public ApiResponse<Void> deleteTool(@PathVariable String toolId) {
        access.requireRole("ADMIN");
        tools.remove(toolId);
        return ApiResponse.success(null);
    }

    @GetMapping("/tools/traces")
    public ApiResponse<List<ToolCallTrace>> toolTraces() {
        access.requireRole("ADMIN");
        return ApiResponse.success(traces.list());
    }

    @GetMapping("/approvals")
    public ApiResponse<List<ApprovalView>> approvals() {
        return ApiResponse.success(approvals.pendingFor(access.requireRole("APPROVER")).stream()
                .map(ApprovalView::from).toList());
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public ApiResponse<?> approve(@PathVariable String approvalId,
            @RequestBody(required = false) ApprovalContinueRequest request) {
        return request == null ? decide(approvalId, true) : continueDecision(approvalId, request, true);
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public ApiResponse<?> reject(@PathVariable String approvalId,
            @RequestBody(required = false) ApprovalContinueRequest request) {
        return request == null ? decide(approvalId, false) : continueDecision(approvalId, request, false);
    }

    private ApiResponse<ApprovalRecord> decide(String approvalId, boolean approved) {
        RequestUser user = access.requireRole("APPROVER");
        ApprovalRecord record = approvals.get(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("审批记录不存在"));
        if (record.datasetId() != null) access.requireDatasetAccess(record.datasetId());
        approvals.validateDecision(approvalId, user.id(), record.datasetId(), record.conversationId());
        return ApiResponse.success(approvals.decide(approvalId, approved, user.id()));
    }

    private ApiResponse<AgentResult> continueDecision(String approvalId, ApprovalContinueRequest request,
            boolean approved) {
        RequestUser user = access.requireRole("APPROVER");
        ApprovalRecord record = approvals.get(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("审批记录不存在"));
        long datasetId = record.datasetId() == null ? request.datasetId() : record.datasetId();
        Long conversationId = record.conversationId() == null ? request.conversationId() : record.conversationId();
        access.requireDatasetAccess(datasetId);
        approvals.validateDecision(approvalId, user.id(), datasetId, conversationId);
        String requesterId = record.requesterUserId() == null ? user.id() : record.requesterUserId();
        return ApiResponse.success(agent.continueAfterApproval(approvalId,
                new QaRequest(datasetId, request.question(), conversationId, requesterId, user.roles()), approved,
                user.id()));
    }

    private ToolView view(ToolDefinition tool) {
        return new ToolView(tool.name(), tool.description(), tool.riskLevel(), tool.enabled(), tool.type(),
                tool.endpoint(), tool.authHeaderName(), tool.jsonSchema(), tool.allowedRoles(),
                tool.allowedDatasetIds(), tool.idempotent());
    }

    private boolean allowed(ToolDefinition tool, RequestUser user) {
        boolean roleAllowed = tool.allowedRoles().isEmpty()
                || tool.allowedRoles().stream().anyMatch(user::hasRole);
        boolean datasetAllowed = tool.allowedDatasetIds().isEmpty()
                || tool.allowedDatasetIds().stream().anyMatch(user::canAccess);
        return roleAllowed && datasetAllowed;
    }

    public record ToolView(String name, String description, String riskLevel, boolean enabled, String type,
            String endpoint, String authHeaderName, String jsonSchema, Set<String> allowedRoles,
            Set<Long> allowedDatasetIds, boolean idempotent) {}

    public record ToolRequest(@NotBlank String name, String description, String riskLevel, boolean enabled,
            String type, String endpoint, String authHeaderName, String authHeaderValue, String jsonSchema,
            Set<String> allowedRoles, Set<Long> allowedDatasetIds, boolean idempotent) {
        ToolDefinition definition() { return definition(name); }
        ToolDefinition definition(String toolName) { return definition(toolName, authHeaderValue); }
        ToolDefinition definition(String toolName, String secretValue) {
            return new ToolDefinition(toolName, description, riskLevel, enabled, type, endpoint,
                    authHeaderName, secretValue, jsonSchema,
                    allowedRoles == null ? Set.of() : allowedRoles,
                    allowedDatasetIds == null ? Set.of() : allowedDatasetIds, idempotent);
        }
    }

    public record ApprovalView(String id, String executionId, String toolName, String params, String status,
            String approvedBy, String requesterUserId, Long datasetId, Long conversationId,
            String createdAt, String expiresAt) {
        static ApprovalView from(ApprovalSnapshot value) {
            return new ApprovalView(value.id(), value.executionId(), value.toolName(), value.params(), value.status(),
                    value.approvedBy(), value.requesterUserId(), value.datasetId(), value.conversationId(),
                    value.createdAt(), value.expiresAt());
        }
    }

    public record ApprovalContinueRequest(long datasetId, String question, Long conversationId) {}
}
