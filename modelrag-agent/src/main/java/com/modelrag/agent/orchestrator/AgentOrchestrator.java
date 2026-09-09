package com.modelrag.agent.orchestrator;

import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.intent.IntentNode;
import com.modelrag.agent.intent.IntentTreeService;
import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.agent.memory.LongTermMemoryService;
import com.modelrag.agent.router.ComplexityRouter;
import com.modelrag.agent.router.RouteDecision;
import com.modelrag.agent.runtime.AgentRuntime;
import com.modelrag.agent.runtime.AgentStartCommand;
import com.modelrag.agent.safety.LoopDetector;
import com.modelrag.agent.tool.HttpToolInvoker;
import com.modelrag.agent.tool.ResilientToolExecutor;
import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.agent.tool.ToolRegistry;
import com.modelrag.agent.trace.AgentStepTracer;
import com.modelrag.agent.trace.ToolCallTrace;
import com.modelrag.agent.trace.ToolCallTracer;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.operation.OperationGuard;
import com.modelrag.common.sse.SseEmitterService;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bounded ReAct execution. Only safe action summaries are published to clients.
 */
@Service
public class AgentOrchestrator {
    private final ComplexityRouter router;
    private final QaOrchestrator qa;
    private final ApprovalGate approvals;
    private final ToolRegistry tools;
    private final LoopDetector loops;
    private final ConversationMemory memory;
    private final LongTermMemoryService longTermMemory;
    private final SseEmitterService sse;
    private final ToolCallTracer tracer;
    private final AgentStepTracer stepTracer;
    private final IntentTreeService intents;
    private final ResilientToolExecutor executor;
    private final HttpToolInvoker httpTools;
    private final AgentPlanner planner;
    private final DatasetRepository datasets;
    private final AgentExecutionRegistry executions;
    private final OperationGuard operationGuard;
    private final ObjectProvider<AgenticRetrievalOrchestrator> agenticRetrieval;
    private final int maxSteps;
    private final long deadlineMs;
    private final boolean legacyNonDurableFallback;
    private AgentRuntime durableRuntime;

    public AgentOrchestrator(ComplexityRouter router, QaOrchestrator qa, ApprovalGate approvals,
                             ToolRegistry tools, LoopDetector loops, ConversationMemory memory,
                             LongTermMemoryService longTermMemory, SseEmitterService sse, ToolCallTracer tracer,
                             AgentStepTracer stepTracer, IntentTreeService intents, ResilientToolExecutor executor,
                             HttpToolInvoker httpTools, AgentPlanner planner, DatasetRepository datasets,
                             AgentExecutionRegistry executions, OperationGuard operationGuard,
                             ObjectProvider<AgenticRetrievalOrchestrator> agenticRetrieval,
                             @Value("${modelrag.agent.max-steps:6}") int maxSteps,
                             @Value("${modelrag.agent.deadline-ms:10000}") long deadlineMs) {
        this(router, qa, approvals, tools, loops, memory, longTermMemory, sse, tracer, stepTracer, intents,
                executor, httpTools, planner, datasets, executions, operationGuard, agenticRetrieval,
                maxSteps, deadlineMs, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentOrchestrator(ComplexityRouter router, QaOrchestrator qa, ApprovalGate approvals,
                             ToolRegistry tools, LoopDetector loops, ConversationMemory memory,
                             LongTermMemoryService longTermMemory, SseEmitterService sse, ToolCallTracer tracer,
                             AgentStepTracer stepTracer, IntentTreeService intents, ResilientToolExecutor executor,
                             HttpToolInvoker httpTools, AgentPlanner planner, DatasetRepository datasets,
                             AgentExecutionRegistry executions, OperationGuard operationGuard,
                             ObjectProvider<AgenticRetrievalOrchestrator> agenticRetrieval,
                             @Value("${modelrag.agent.max-steps:6}") int maxSteps,
                             @Value("${modelrag.agent.deadline-ms:10000}") long deadlineMs,
                             @Value("${modelrag.agent.legacy-non-durable-fallback:false}") boolean legacyNonDurableFallback) {
        this.router = router;
        this.qa = qa;
        this.approvals = approvals;
        this.tools = tools;
        this.loops = loops;
        this.memory = memory;
        this.longTermMemory = longTermMemory;
        this.sse = sse;
        this.tracer = tracer;
        this.stepTracer = stepTracer;
        this.intents = intents;
        this.executor = executor;
        this.httpTools = httpTools;
        this.planner = planner;
        this.datasets = datasets;
        this.executions = executions;
        this.operationGuard = operationGuard;
        this.agenticRetrieval = agenticRetrieval;
        this.maxSteps = Math.max(1, Math.min(10, maxSteps));
        this.deadlineMs = Math.max(500, Math.min(60_000, deadlineMs));
        this.legacyNonDurableFallback = legacyNonDurableFallback;
    }

    /** Optional only for the explicit test compatibility profile; production fails closed when absent. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDurableRuntime(AgentRuntime durableRuntime) {
        this.durableRuntime = durableRuntime;
    }

    public AgentResult execute(QaRequest request) {
        return executeInternal(request, UUID.randomUUID().toString(), true);
    }

    /**
     * Executes with a caller-supplied id and persists the execution record before running.
     */
    public AgentResult execute(QaRequest request, String executionId) {
        return executeInternal(request, executionId, true);
    }

    /**
     * Used by streaming callers that pre-register the id so cancellation can win the queue race.
     */
    public AgentResult executeRegistered(QaRequest request, String executionId) {
        return executeInternal(request, executionId, false);
    }

    public void registerExecution(QaRequest request, String executionId) {
        executions.register(executionId, request.userId(), request.datasetId(), request.conversationId());
    }

    private AgentResult executeInternal(QaRequest request, String executionId, boolean register) {
        if (register) executions.register(executionId, request.userId(), request.datasetId(), request.conversationId());
        Thread thread = Thread.currentThread();
        boolean bound = false;
        try {
            executions.bind(executionId, thread);
            bound = true;
            return executeBound(request, executionId);
        } catch (RuntimeException error) {
            if (!bound) executions.complete(executionId, "ERROR");
            throw error;
        } finally {
            if (bound) executions.unbind(executionId, thread);
        }
    }

    private AgentResult executeBound(QaRequest request, String executionId) {
        IntentNode intent = intents.match(request.datasetId(), request.query()).orElse(null);
        RouteDecision route = intent != null && "TOOL".equals(intent.targetType())
                ? RouteDecision.TOOL_AGENT
                : intent != null && "DIRECT".equals(intent.targetType())
                        ? RouteDecision.DIRECT_RAG : router.route(request.query());
        if (executions.cancelRequested(executionId)) {
            return cancelled(executionId, route, request, null, new ArrayList<>(), List.of());
        }

        if (route == RouteDecision.DIRECT_RAG) {
            QaResult result = qa.answer(request);
            if (Thread.currentThread().isInterrupted() || executions.cancelRequested(executionId)) {
                return cancelled(executionId, route, request, null, new ArrayList<>(), List.of());
            }
            publish(executionId, "PLAN", "已选择直接知识库问答", Map.of("route", "DIRECT_RAG"));
            publish(executionId, "ANSWER", "已完成知识库回答", Map.of("traceId", safeTraceId(result)));
            publish(executionId, "DONE", "执行完成", Map.of("route", "DIRECT_RAG", "traceId", safeTraceId(result)));
            executions.complete(executionId, "DONE");
            return new AgentResult(executionId, route, "DONE", result.answer(), null,
                    List.of("PLAN", "ANSWER"), result.citations(), result.confidence(), result.refused(), result.traceId());
        }

        if (route == RouteDecision.AGENTIC_RAG) {
            return executeAgenticRetrieval(request, executionId);
        }

        // Persist the user turn before planning or invoking any tool. Agent sub-steps
        // retain this conversation id for context but are explicitly read-only turns.
        rememberAgentQuestion(request);

        IntentNode matchedIntent = intent;
        boolean toolIntent = matchedIntent != null && "TOOL".equals(matchedIntent.targetType());
        String fallbackTool;
        if (toolIntent) {
            if (matchedIntent.targetId() == null || matchedIntent.targetId().isBlank()) {
                return unresolvedTool(executionId, request, new ArrayList<>());
            }
            fallbackTool = matchedIntent.targetId();
        } else if (queryIsHighRisk(request.query())) {
            fallbackTool = "destructive_operation";
        } else {
            return unresolvedTool(executionId, request, new ArrayList<>());
        }
        List<String> parts = subtasks(request.query());
        ToolDefinition definition;
        try {
            definition = tools.get(fallbackTool);
        } catch (RuntimeException error) {
            return unresolvedTool(executionId, request, new ArrayList<>());
        }
        if (durableRuntime != null) {
            AgentResult result = durableRuntime.start(new AgentStartCommand(request, executionId,
                    "TOOL_AGENT", fallbackTool, (type, message, data) -> publish(executionId, type, message, data)));
            rememberDurableResult(request, result);
            return result;
        }
        if (!legacyNonDurableFallback) return durableUnavailable(executionId, RouteDecision.TOOL_AGENT, request);
        List<String> steps = new ArrayList<>();
        steps.add("PLAN");
        publish(executionId, "PLAN", "已选择受限 Agent 执行", Map.of(
                "route", "TOOL_AGENT", "tool", fallbackTool, "maxSteps", maxSteps,
                "subtaskCount", parts.size()));
        if (requiresApproval(definition)) {
            operationGuard.requireAvailableForSideEffect();
            ApprovalRecord approval = approvals.request(executionId, fallbackTool,
                    "{\"query\":\"" + escape(request.query()) + "\"}", request.userId(),
                    request.datasetId(), request.conversationId());
            steps.add("APPROVAL_REQUIRED");
            rememberApprovalWait(request, approval.id());
            publish(executionId, "APPROVAL_REQUIRED", "高风险工具需要审批", Map.of(
                    "approvalId", approval.id(), "ttlSeconds", 300,
                    "requesterUserId", safe(request.userId()), "datasetId", request.datasetId()));
            return new AgentResult(executionId, route, "WAITING_APPROVAL", null, approval.id(), steps);
        }
        return reactLoop(executionId, route, request, null, fallbackTool, steps, false, parts);
    }

    private AgentResult executeAgenticRetrieval(QaRequest request, String executionId) {
        rememberAgentQuestion(request);
        publish(executionId, "PLAN", "已选择只读 Agentic Retrieval", Map.of(
                "route", "AGENTIC_RAG", "maxSteps", maxSteps));
        if (durableRuntime != null) {
            AgentResult result = durableRuntime.start(new AgentStartCommand(request, executionId,
                    "AGENTIC_RAG", "", (type, message, data) -> publish(executionId, type, message, data)));
            rememberDurableResult(request, result);
            return result;
        }
        if (!legacyNonDurableFallback) return durableUnavailable(executionId, RouteDecision.AGENTIC_RAG, request);
        AgenticRetrievalOrchestrator orchestrator = agenticRetrieval.getIfAvailable();
        if (orchestrator == null) {
            String answer = "只读检索 Agent 当前不可用。";
            qa.recordAudit(request, answer, "[]", 0, true, executionId, "agentic_rag");
            publish(executionId, "ERROR", "只读检索 Agent 不可用", Map.of("route", "AGENTIC_RAG"));
            executions.complete(executionId, "ERROR");
            return new AgentResult(executionId, RouteDecision.AGENTIC_RAG, "ERROR", answer, null,
                    List.of("PLAN", "ERROR"), List.of(), 0, true, executionId,
                    List.of("agentic-retrieval-unavailable"));
        }
        AgenticRetrievalResult result = orchestrator.execute(request, executionId,
                () -> executions.cancelRequested(executionId),
                (type, message, data) -> publish(executionId, type, message, data));
        rememberAgentAnswer(request, result.answer(), result.traceId(), result.citations(), false);
        qa.recordAudit(request, result.answer(), citationsJson(result.citations()), result.confidence(),
                result.refused(), result.traceId(), "agentic_rag");
        if ("DONE".equals(result.status())) {
            publish(executionId, result.refused() ? "OBSERVE" : "ANSWER",
                    result.refused() ? "证据不足，未生成答案" : "已根据 EvidenceSet 形成答案",
                    Map.of("traceId", result.traceId(), "refused", result.refused(),
                            "citationCount", result.citations().size()));
        } else {
            publish(executionId, "ERROR", "Agentic Retrieval 已停止", Map.of("status", result.status()));
        }
        publish(executionId, "DONE", "Agentic Retrieval 完成", Map.of(
                "route", "AGENTIC_RAG", "status", result.status(), "traceId", result.traceId()));
        executions.complete(executionId, result.status());
        return new AgentResult(executionId, RouteDecision.AGENTIC_RAG, result.status(), result.answer(), null,
                result.steps(), result.citations(), result.confidence(), result.refused(), result.traceId(),
                result.degradedComponents());
    }

    public AgentResult continueAfterApproval(String approvalId, QaRequest request, boolean approved) {
        return continueAfterApproval(approvalId, request, approved, request.userId());
    }

    public AgentResult continueAfterApproval(String approvalId, QaRequest request, boolean approved,
                                             String approverId) {
        ApprovalRecord record = approvals.decide(approvalId, approved, approverId);
        if (durableRuntime != null) {
            AgentResult result = durableRuntime.resumeAfterApproval(approvalId);
            rememberDurableResult(request, result);
            return result;
        }
        if (!"APPROVED".equals(record.status())) {
            String answer = "工具调用未获批准。";
            rememberApprovalDecision(request, answer, null);
            publish(record.executionId(), "ERROR", "工具调用未获批准", Map.of("status", record.status()));
            return new AgentResult(record.executionId(), RouteDecision.TOOL_AGENT, record.status(), answer,
                    record.id(), List.of("APPROVAL_" + record.status()), List.of(), 0, true, null);
        }
        Thread thread = Thread.currentThread();
        boolean bound = false;
        try {
            executions.bind(record.executionId(), thread);
            bound = true;
            return reactLoop(record.executionId(), RouteDecision.TOOL_AGENT, request, record.id(),
                    record.toolName(), new ArrayList<>(List.of("PLAN", "APPROVAL_APPROVED")), false,
                    subtasks(request.query()));
        } catch (RuntimeException error) {
            if (!bound) executions.complete(record.executionId(), "ERROR");
            throw error;
        } finally {
            if (bound) executions.unbind(record.executionId(), thread);
        }
    }

    private AgentResult reactLoop(String executionId, RouteDecision route, QaRequest request,
                                  String approvalId, String fallbackTool, List<String> steps, boolean rememberQuestion,
                                  List<String> parts) {
        long deadline = System.nanoTime() + deadlineMs * 1_000_000;
        List<QaResult> results = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        List<String> actionFingerprints = new ArrayList<>();
        String previousObservation = null;
        int unchangedObservations = 0;

        for (int index = 0; index < maxSteps; index++) {
            if (executions.cancelRequested(executionId)) {
                executions.complete(executionId, "CANCELLED");
                return cancelled(executionId, route, request, approvalId, steps, results);
            }
            if (System.nanoTime() > deadline) {
                executions.complete(executionId, "TIMEOUT");
                return timeout(executionId, route, request, approvalId, steps, results,
                        "Agent 总执行时间已达到限制");
            }
            AgentPlanner.Plan decision = planner.reactStep(request.userId(), request.query(), observations, fallbackTool,
                    parts, availableTools(request, fallbackTool), index);
            if (decision.subtasks().isEmpty()) {
                steps.add("PLAN_DONE");
                publish(executionId, "PLAN", "已有足够观察，结束工具循环", Map.of("step", index + 1));
                break;
            }
            String tool = decision.toolName();
            String subtask = decision.subtasks().get(0);
            if ("knowledge_lookup".equals(tool) && !"knowledge_lookup".equals(fallbackTool)) {
                return unresolvedTool(executionId, request, steps);
            }
            ToolDefinition definition = tools.get(tool);
            if (requiresApproval(definition) && approvalId == null) {
                operationGuard.requireAvailableForSideEffect();
                ApprovalRecord approval = approvals.request(executionId, tool,
                        "{\"query\":\"" + escape(request.query()) + "\"}", request.userId(),
                        request.datasetId(), request.conversationId());
                steps.add("APPROVAL_REQUIRED");
                rememberApprovalWait(request, approval.id());
                publish(executionId, "APPROVAL_REQUIRED", "高风险工具需要审批", Map.of(
                        "approvalId", approval.id(), "ttlSeconds", 300, "datasetId", request.datasetId()));
                return new AgentResult(executionId, route, "WAITING_APPROVAL", null, approval.id(), steps);
            }
            if (requiresApproval(definition)) operationGuard.requireAvailableForSideEffect();

            String fingerprint = normalize(tool + ":" + toolParams(request, subtask));
            actionFingerprints.add(fingerprint);
            if (loops.detect(actionFingerprints)) {
                steps.add("LOOP_STOPPED");
                publish(executionId, "PLAN", "检测到重复工具请求，已停止循环", Map.of("step", index + 1));
                break;
            }
            steps.add("PLAN");
            publish(executionId, "PLAN", "已生成下一步工具计划", Map.of(
                    "tool", tool, "subtask", limit(subtask, 160), "step", index + 1, "maxSteps", maxSteps));

            long started = System.nanoTime();
            steps.add("ACT:" + tool);
            publish(executionId, "ACT", "正在执行工具 " + tool, Map.of(
                    "toolName", tool, "parameterSummary", limit(subtask, 160), "step", index + 1));
            String toolParams = toolParams(request, subtask);
            try {
                requireToolAccess(definition, request);
                if (requiresApproval(definition)) {
                    operationGuard.claimSideEffect(executionId + ":" + fingerprint);
                }
                QaRequest subRequest = new QaRequest(request.datasetId(), subtask,
                        request.conversationId(), request.userId(), request.userRoles())
                        .withResolvedContext(request.resolvedContext())
                        .withoutConversationMessage();
                String idempotencyKey = executionId + ":" + fingerprint;
                var execution = executor.execute(definition, toolParams,
                        () -> executeTool(definition, subRequest, idempotencyKey));
                if (executions.cancelRequested(executionId)) {
                    executions.complete(executionId, "CANCELLED");
                    return cancelled(executionId, route, request, approvalId, steps, results);
                }
                QaResult result = execution.value();
                results.add(result);
                String observation = observation(subtask, result);
                observations.add(observation);
                if (normalize(observation).equals(previousObservation)) unchangedObservations++;
                else unchangedObservations = 0;
                previousObservation = normalize(observation);
                tracer.record(new ToolCallTrace(executionId, tool, toolParams,
                        toolOutput(result, execution), true, null,
                        (System.nanoTime() - started) / 1_000_000));
                steps.add("OBSERVE");
                publish(executionId, "OBSERVE", result.refused() ? "工具未获得足够证据" : "工具已返回结果", Map.of(
                        "traceId", safeTraceId(result), "refused", result.refused(),
                        "attempts", execution.attempts(), "step", index + 1));
                if (unchangedObservations >= 2) {
                    steps.add("LOOP_STOPPED");
                    publish(executionId, "PLAN", "连续多步没有新观察，已停止循环", Map.of("step", index + 1));
                    break;
                }
            } catch (RuntimeException error) {
                if (Thread.currentThread().isInterrupted() || executions.cancelRequested(executionId)) {
                    executions.complete(executionId, "CANCELLED");
                    return cancelled(executionId, route, request, approvalId, steps, results);
                }
                tracer.record(new ToolCallTrace(executionId, tool, toolParams, "{}", false,
                        error.getMessage(), (System.nanoTime() - started) / 1_000_000));
                publish(executionId, "ERROR", "工具执行失败", Map.of("toolName", tool, "step", index + 1));
                executions.complete(executionId, "ERROR");
                return new AgentResult(executionId, route, "ERROR", "工具执行失败，请稍后重试。",
                        approvalId, steps, citations(results), confidence(results), true, lastTraceId(results));
            }
        }

        if (executions.cancelRequested(executionId)) {
            executions.complete(executionId, "CANCELLED");
            return cancelled(executionId, route, request, approvalId, steps, results);
        }
        String answer = combine(results);
        String traceId = lastTraceId(results);
        List<Citation> citations = citations(results);
        double confidence = confidence(results);
        boolean refused = results.isEmpty() || results.stream().allMatch(QaResult::refused);
        rememberAgentAnswer(request, answer, traceId, citations, rememberQuestion);
        qa.recordAudit(request, answer, citationsJson(citations), confidence, refused,
                traceId.isBlank() ? executionId : traceId, "agent");
        steps.add("ANSWER");
        publish(executionId, "ANSWER", "已根据工具观察形成答案", Map.of(
                "count", results.size(), "maxSteps", maxSteps, "traceId", traceId));
        publish(executionId, "DONE", "Agent 完成", Map.of("traceId", traceId, "status", "DONE"));
        executions.complete(executionId, "DONE");
        return new AgentResult(executionId, route, "DONE", answer, approvalId, steps,
                citations, confidence, refused, traceId);
    }

    private AgentResult timeout(String executionId, RouteDecision route, QaRequest request,
                                String approvalId, List<String> steps, List<QaResult> results, String message) {
        String answer = results.isEmpty() ? "Agent 执行超时，已停止后续工具调用。" : combine(results);
        List<Citation> citations = citations(results);
        String traceId = lastTraceId(results);
        rememberAgentAnswer(request, answer, traceId, citations, false);
        steps.add("TIMEOUT");
        publish(executionId, "ERROR", message, Map.of("timeoutMs", deadlineMs));
        executions.complete(executionId, "TIMEOUT");
        return new AgentResult(executionId, route, "TIMEOUT", answer, approvalId, steps,
                citations, confidence(results), results.isEmpty(), traceId);
    }

    private AgentResult cancelled(String executionId, RouteDecision route, QaRequest request,
                                  String approvalId, List<String> steps, List<QaResult> results) {
        executions.complete(executionId, "CANCELLED");
        String answer = results.isEmpty() ? "Agent 执行已取消。" : combine(results);
        List<Citation> citations = citations(results);
        String traceId = lastTraceId(results);
        rememberAgentAnswer(request, answer, traceId, citations, false);
        steps.add("CANCELLED");
        publish(executionId, "CANCELLED", "Agent 执行已取消", Map.of("status", "CANCELLED"));
        publish(executionId, "DONE", "Agent 已取消", Map.of("status", "CANCELLED"));
        return new AgentResult(executionId, route, "CANCELLED", answer, approvalId, steps,
                citations, confidence(results), true, traceId);
    }

    private QaResult executeTool(ToolDefinition definition, QaRequest request, String idempotencyKey) {
        if (definition.http()) {
            String output = httpTools.invoke(definition, request.query(), idempotencyKey);
            return new QaResult("结论：" + limit(output, 500), List.of(), 1, false, null);
        }
        return qa.answer(withContext(request).withoutConversationMessage());
    }

    private QaRequest withContext(QaRequest request) {
        // QA owns the shared persisted conversation/memory context boundary. Keeping the
        // original query here prevents Agent sub-steps from injecting the same context twice.
        return request;
    }

    private List<ToolDefinition> availableTools(QaRequest request, String fallbackTool) {
        return tools.listEnabled().stream()
                .filter(tool -> !"knowledge_lookup".equals(tool.name()) || "knowledge_lookup".equals(fallbackTool))
                .filter(tool -> canUse(tool, request)).toList();
    }

    private AgentResult unresolvedTool(String executionId, QaRequest request, List<String> steps) {
        List<String> resultSteps = new ArrayList<>(steps == null ? List.of() : steps);
        resultSteps.add("ERROR");
        publish(executionId, "ERROR", "业务工具未解析，已拒绝执行", Map.of(
                "route", "TOOL_AGENT", "reason", "tool-unresolved"));
        executions.complete(executionId, "ERROR");
        return new AgentResult(executionId, RouteDecision.TOOL_AGENT, "ERROR", "tool-unresolved", null,
                resultSteps, List.of(), 0, true, executionId, List.of("tool-unresolved"));
    }

    private AgentResult durableUnavailable(String executionId, RouteDecision route, QaRequest request) {
        String answer = "durable-agent-runtime-unavailable";
        try {
            qa.recordAudit(request, answer, "[]", 0, true, executionId, route.name().toLowerCase());
        } catch (RuntimeException ignored) { }
        publish(executionId, "ERROR", "Durable Agent runtime unavailable", Map.of(
                "route", route.name(), "reason", "durable-agent-runtime-unavailable"));
        executions.complete(executionId, "ERROR");
        return new AgentResult(executionId, route, "ERROR", answer, null,
                List.of("ERROR"), List.of(), 0, true, executionId,
                List.of("durable-agent-runtime-unavailable"));
    }

    private void requireToolAccess(ToolDefinition tool, QaRequest request) {
        if (!canUse(tool, request)) throw new IllegalStateException("用户无权调用工具: " + tool.name());
    }

    private boolean canUse(ToolDefinition tool, QaRequest request) {
        boolean datasetOk = tool.allowedDatasetIds() == null || tool.allowedDatasetIds().isEmpty()
                || tool.allowedDatasetIds().contains(request.datasetId());
        if (!datasetOk) return false;
        Set<String> roles = request.userRoles() == null ? Set.of() : request.userRoles();
        return tool.allowedRoles() == null || tool.allowedRoles().isEmpty()
                || roles.stream().anyMatch(tool.allowedRoles()::contains);
    }

    private boolean queryIsHighRisk(String query) {
        String value = query == null ? "" : query;
        return value.contains("删除") || value.contains("变更") || value.contains("提交审批")
                || value.contains("发起审批") || (value.contains("请审批") && !value.contains("谁"));
    }

    private boolean requiresApproval(ToolDefinition definition) {
        return definition != null && ("HIGH".equalsIgnoreCase(definition.riskLevel())
                || "EXTERNAL_SIDE_EFFECT".equalsIgnoreCase(definition.riskLevel()));
    }

    private List<String> subtasks(String query) {
        String[] parts = (query == null ? "" : query).split("并且|然后|同时|以及|；|;");
        List<String> result = new ArrayList<>();
        for (String part : parts) if (!part.isBlank()) result.add(part.trim());
        return (result.isEmpty() ? List.of(query == null ? "" : query) : result).stream()
                .filter(part -> !part.isBlank()).limit(maxSteps).toList();
    }

    private String combine(List<QaResult> results) {
        if (results.isEmpty()) return "Agent 未获得可用观察结果。";
        List<QaResult> usable = results.stream().filter(result -> !result.refused()).toList();
        if (usable.isEmpty()) return results.get(0).answer();
        if (usable.size() == 1) return usable.get(0).answer();
        return usable.stream().map(QaResult::answer)
                .map(answer -> answer.replaceFirst("^结论[:：]", "").trim())
                .collect(Collectors.joining("\n"));
    }

    private List<Citation> citations(List<QaResult> results) {
        return results.stream().filter(result -> !result.refused()).flatMap(result -> result.citations().stream())
                .collect(Collectors.toMap(Citation::chunkId, citation -> citation,
                        (left, right) -> left, java.util.LinkedHashMap::new))
                .values().stream().limit(5).toList();
    }

    private double confidence(List<QaResult> results) {
        return results.stream().mapToDouble(QaResult::confidence).max().orElse(0);
    }

    private String lastTraceId(List<QaResult> results) {
        for (int index = results.size() - 1; index >= 0; index--) {
            String value = safeTraceId(results.get(index));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String toolOutput(QaResult result, ResilientToolExecutor.Result<QaResult> execution) {
        return "{\"traceId\":\"" + escape(result.traceId()) + "\",\"refused\":" + result.refused()
                + ",\"attempts\":" + execution.attempts() + ",\"reused\":" + execution.reused()
                + ",\"answer\":\"" + escape(result.answer()) + "\"}";
    }

    private String toolParams(QaRequest request, String subtask) {
        return "{\"query\":\"" + escape(subtask) + "\",\"datasetId\":" + request.datasetId()
                + ",\"userId\":\"" + escape(request.userId()) + "\",\"conversationId\":\""
                + escape(request.conversationId() == null ? "" : String.valueOf(request.conversationId())) + "\"}";
    }

    private String observation(String subtask, QaResult result) {
        return "query=" + subtask + " refused=" + result.refused() + " traceId=" + safeTraceId(result)
                + " answer=" + limit(result.answer(), 180);
    }

    private String safeTraceId(QaResult result) {
        return result.traceId() == null ? "" : result.traceId();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private String limit(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String datasetName(long datasetId) {
        try {
            return datasets.findById(datasetId).name();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void rememberApprovalWait(QaRequest request, String approvalId) {
        if (request.conversationId() == null) return;
        memory.append(request.userId(), request.conversationId(), "assistant",
                "高风险请求已进入 Agent，等待审批后继续执行。审批号 " + shortId(approvalId),
                "[]", null, "agent", datasetName(request.datasetId()));
    }

    private void rememberAgentQuestion(QaRequest request) {
        if (request.conversationId() == null) return;
        memory.append(request.userId(), request.conversationId(), "user", request.query(),
                "[]", null, "agent", datasetName(request.datasetId()));
    }

    private void rememberApprovalDecision(QaRequest request, String answer, String traceId) {
        if (request.conversationId() != null)
            memory.append(request.userId(), request.conversationId(), "assistant", answer,
                    "[]", traceId, "agent", datasetName(request.datasetId()));
    }

    private void rememberAgentAnswer(QaRequest request, String answer, String traceId,
                                     List<Citation> citations, boolean rememberQuestion) {
        if (request.conversationId() == null) return;
        if (rememberQuestion) memory.append(request.userId(), request.conversationId(), "user", request.query(),
                "[]", null, "agent", datasetName(request.datasetId()));
        memory.append(request.userId(), request.conversationId(), "assistant", answer, citationsJson(citations), traceId,
                "agent", datasetName(request.datasetId()));
    }

    private void rememberDurableResult(QaRequest request, AgentResult result) {
        if (result == null) return;
        if ("WAITING_APPROVAL".equals(result.status())) {
            if (result.approvalId() != null) rememberApprovalWait(request, result.approvalId());
            return;
        }
        rememberAgentAnswer(request, result.answer(), result.traceId(), result.citations(), false);
        qa.recordAudit(request, result.answer(), citationsJson(result.citations()), result.confidence(),
                result.refused(), result.traceId(), result.route().name().toLowerCase());
    }

    private String citationsJson(List<Citation> citations) {
        return citations.stream().map(c -> "{\"chunkId\":" + c.chunkId() + ",\"excerpt\":\""
                        + escape(c.excerpt()) + "\",\"score\":" + c.score() + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String shortId(String value) {
        return value == null || value.length() <= 8 ? String.valueOf(value) : value.substring(0, 8);
    }

    private String escape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void publish(String executionId, String type, String message, Map<String, Object> data) {
        stepTracer.record(executionId, type, message, data, 0);
        sse.publish("agent:" + executionId, new SseEvent(type, message, data));
    }
}
