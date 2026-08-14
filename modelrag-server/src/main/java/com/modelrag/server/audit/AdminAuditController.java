package com.modelrag.server.audit;

import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.trace.AgentStepTrace;
import com.modelrag.agent.trace.AgentStepTracer;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.qa.trace.QaAuditView;
import com.modelrag.qa.trace.QaTraceView;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational read model. Access control is supplied by the deployment's future identity layer. */
@RestController @RequestMapping({"/api/v1/admin", "/api/v2/admin"}) public class AdminAuditController {
    private final QaOrchestrator qa; private final ApprovalGate approvals; private final AgentStepTracer agentSteps; private final AccessControlService access;
    public AdminAuditController(QaOrchestrator qa,ApprovalGate approvals,AgentStepTracer agentSteps,AccessControlService access){this.qa=qa;this.approvals=approvals;this.agentSteps=agentSteps;this.access=access;}
    @GetMapping("/qa-audits") public ApiResponse<List<QaAuditView>> qaAudits(){access.requireRole("ADMIN");return ApiResponse.success(qa.audits().stream().map(QaAuditView::from).toList());}
    @GetMapping("/retrieval-traces/{traceId}/replay") public ApiResponse<QaTraceView> replay(@PathVariable String traceId){access.requireRole("ADMIN");return ApiResponse.success(QaTraceView.from(qa.replayTrace(traceId)));}
    @GetMapping("/approvals") public ApiResponse<List<com.modelrag.agent.approval.ApprovalSnapshot>> approvals(){access.requireRole("ADMIN");return ApiResponse.success(approvals.list());}
    @GetMapping("/agent-steps") public ApiResponse<List<AgentStepTrace>> agentSteps(){access.requireRole("ADMIN");return ApiResponse.success(agentSteps.list());}
    @GetMapping("/agent-steps/{executionId}") public ApiResponse<List<AgentStepTrace>> agentSteps(@PathVariable String executionId){access.requireRole("ADMIN");return ApiResponse.success(agentSteps.list(executionId));}
}
