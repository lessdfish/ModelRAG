package com.modelrag.agent.controller;

import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.orchestrator.AgentExecutionRegistry;
import com.modelrag.agent.orchestrator.AgentOrchestrator;
import com.modelrag.agent.orchestrator.AgentResult;
import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.sse.SseEmitterService;
import com.modelrag.qa.dto.QaRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController @RequestMapping("/api/v1/qa/agent")
public class AgentController {private final AgentOrchestrator agent;private final SseEmitterService sse;private final AccessControlService access;private final ConversationMemory memory;private final ApprovalGate approvals;private final AgentExecutionRegistry executions;public AgentController(AgentOrchestrator a,SseEmitterService s,AccessControlService access,ConversationMemory memory,ApprovalGate approvals,AgentExecutionRegistry executions){agent=a;sse=s;this.access=access;this.memory=memory;this.approvals=approvals;this.executions=executions;}@PostMapping public ApiResponse<AgentResult> execute(@RequestBody QaRequest request){var user=access.currentUser();access.requireDatasetAccess(request.datasetId());memory.requireOwner(user.id(),request.conversationId());return ApiResponse.success(agent.execute(new QaRequest(request.datasetId(),request.query(),request.conversationId(),user.id(),user.roles())));}@GetMapping(value="/{executionId}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)public SseEmitter stream(@PathVariable String executionId){var user=access.currentUser();executions.requireSubscribe(user,executionId);return sse.subscribe("agent:"+executionId);}@GetMapping("/approvals")public ApiResponse<List<Map<String,Object>>> approvals(){var user=access.requireRole("APPROVER");return ApiResponse.success(approvals.pendingFor(user));}@PostMapping("/approval/{approvalId}")public ApiResponse<AgentResult> approve(@PathVariable String approvalId,@RequestBody QaRequest request,@RequestParam(defaultValue="true") boolean approved){var user=access.requireRole("APPROVER");ApprovalRecord record=approvals.validateDecision(approvalId,user.id(),request.datasetId(),request.conversationId());long datasetId=record.datasetId()==null?request.datasetId():record.datasetId();Long conversationId=record.conversationId()==null?request.conversationId():record.conversationId();String requesterId=record.requesterUserId()==null?request.userId():record.requesterUserId();access.requireDatasetAccess(datasetId);return ApiResponse.success(agent.continueAfterApproval(approvalId,new QaRequest(datasetId,request.query(),conversationId,requesterId,user.roles()),approved,user.id()));}}
