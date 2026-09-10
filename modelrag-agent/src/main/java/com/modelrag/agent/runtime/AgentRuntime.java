package com.modelrag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.orchestrator.AgentResult;
import com.modelrag.agent.trace.AgentStepTracer;
import com.modelrag.agent.router.RouteDecision;
import com.modelrag.common.operation.OperationGuard;
import com.modelrag.common.observability.TraceCorrelation;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** The single durable owner of AGENTIC_RAG and TOOL_AGENT state transitions. */
@Service
@Profile("!test")
public class AgentRuntime {
    private static final ScheduledExecutorService LEASE_HEARTBEATS =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("modelrag-agent-lease-heartbeat").factory());
    private final AgentCheckpointService checkpoints;
    private final com.modelrag.agent.runtime.repository.AgentExecutionRepository executions;
    private final List<AgentModeHandler> handlers;
    private final ApprovalGate approvals;
    private final OperationGuard operationGuard;
    private final AgentRuntimeOwner owner;
    private final ObjectMapper json;
    private final AgentStepTracer stepTracer;
    private final int maxSteps;
    private final long deadlineMs;
    private final int maxSearchActions;
    private final int maxNavigationActions;
    private volatile MeterRegistry metrics;

    public AgentRuntime(AgentCheckpointService checkpoints,
            com.modelrag.agent.runtime.repository.AgentExecutionRepository executions,
            List<AgentModeHandler> handlers, ApprovalGate approvals, OperationGuard operationGuard,
            ObjectMapper json, int maxSteps, long deadlineMs) {
        this(checkpoints, executions, handlers, approvals, operationGuard, json, maxSteps, deadlineMs, 4, 12,
                null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRuntime(AgentCheckpointService checkpoints,
            com.modelrag.agent.runtime.repository.AgentExecutionRepository executions,
            List<AgentModeHandler> handlers, ApprovalGate approvals, OperationGuard operationGuard,
            ObjectMapper json, @Value("${modelrag.agent.max-steps:6}") int maxSteps,
            @Value("${modelrag.agent.deadline-ms:10000}") long deadlineMs,
            @Value("${modelrag.agent.retrieval.max-search-actions:4}") int maxSearchActions,
            @Value("${modelrag.agent.retrieval.max-navigation-actions:12}") int maxNavigationActions,
            AgentStepTracer stepTracer) {
        this.checkpoints = checkpoints;
        this.executions = executions;
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
        this.approvals = approvals;
        this.operationGuard = operationGuard;
        this.owner = new AgentRuntimeOwner();
        this.json = json;
        this.stepTracer = stepTracer;
        this.maxSteps = Math.max(1, Math.min(10, maxSteps));
        this.deadlineMs = Math.max(500, Math.min(60_000, deadlineMs));
        this.maxSearchActions = Math.max(0, Math.min(100, maxSearchActions));
        this.maxNavigationActions = Math.max(0, Math.min(100, maxNavigationActions));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(MeterRegistry metrics) { this.metrics = metrics; }

    public AgentResult start(AgentStartCommand command) {
        AgentState state = initial(command);
        Optional<com.modelrag.agent.runtime.repository.AgentExecutionRecord> existing =
                executions.findById(command.executionId());
        if (existing.isPresent()) {
            verifyScope(existing.get(), command.request());
            if (terminal(existing.get().status()) || "CANCEL_REQUESTED".equals(existing.get().status())
                    || "WAITING_APPROVAL".equals(existing.get().status())) {
                return resultFrom(existing.get(), checkpoints.loadLatest(command.executionId()).orElse(null));
            }
            Optional<AgentState> latest = checkpoints.loadLatest(command.executionId());
            if (latest.isPresent()) {
                // A client retry never starts a second writer. Recovery owns continuation.
                return resultFrom(existing.get(), latest.get());
            }
            if (!checkpoints.tryClaim(command.executionId(), owner.value())) {
                return running(command.executionId(), command.mode());
            }
            metric("modelrag.agent.lease_takeover");
            state = checkpoint(state);
        } else {
            state = checkpoints.createAndCheckpoint(state, owner.value());
            recordCheckpoint(state);
        }
        return run(state, false, command.events());
    }

    public AgentResult resume(String executionId, ResumeReason reason) {
        var row = executions.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("agent execution does not exist"));
        Optional<AgentState> latest = checkpoints.loadLatest(executionId);
        if (latest.isEmpty()) throw new IllegalStateException("agent execution has no checkpoint");
        if (terminal(row.status()) || "CANCEL_REQUESTED".equals(row.status())
                || "WAITING_APPROVAL".equals(row.status())) return resultFrom(row, latest.get());
        if (!checkpoints.tryClaim(executionId, owner.value())) return running(executionId, latest.get().mode());
        metric("modelrag.agent.lease_takeover");
        recordResume(executionId, latest.get(), reason);
        return run(latest.get(), true, com.modelrag.agent.orchestrator.AgenticRetrievalEventSink.NOOP);
    }

    public AgentResult resumeAfterApproval(String approvalId) {
        ApprovalRecord approval = approvals.get(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval record does not exist"));
        var row = executions.findById(approval.executionId())
                .orElseThrow(() -> new IllegalArgumentException("agent execution does not exist"));
        AgentState state = checkpoints.loadLatest(approval.executionId())
                .orElseThrow(() -> new IllegalStateException("agent execution has no checkpoint"));
        if (terminal(row.status()) && state.result() != null) return resultFrom(row, state);
        if (!Objects.equals(state.approvalId(), approvalId) || state.pendingAction() == null) {
            throw new IllegalStateException("approval does not match pending agent action");
        }
        if (!Objects.equals(state.pendingAction().actionName(), approval.toolName())) {
            throw new IllegalStateException("approval tool does not match pending agent action");
        }
        if ("PENDING".equals(approval.status())) return resultFrom(row, state);
        if (!checkpoints.tryClaimWaiting(approval.executionId(), owner.value())) {
            return resultFrom(row, state);
        }
        ResumeReason resumeReason = "APPROVED".equals(approval.status())
                ? ResumeReason.APPROVAL_APPROVED : ResumeReason.APPROVAL_REJECTED;
        recordResume(approval.executionId(), state, resumeReason);
        if (!"APPROVED".equals(approval.status())) {
            AgentState terminal = state.toBuilder().status("TIMEOUT".equals(approval.status())
                            ? AgentRuntimeStatus.TIMEOUT : AgentRuntimeStatus.ERROR)
                    .pendingAction(null).approvalId(approvalId)
                    .result(new AgentResultSnapshot("REJECTED", "工具调用未获批准。", List.of(), 0, true,
                            string(state.toolState().get("traceId")), append(state.degradedComponents(),
                                    "approval-" + approval.status().toLowerCase())))
                    .build();
            AgentState saved = checkpoint(terminal);
            checkpoints.release(saved.executionId(), owner.value());
            return toResult(saved);
        }
        AgentState resumed = state.toBuilder().status(AgentRuntimeStatus.RUNNING).build();
        resumed = checkpoint(resumed);
        return run(resumed, false, com.modelrag.agent.orchestrator.AgenticRetrievalEventSink.NOOP);
    }

    public List<AgentResult> recoverBatch(int batchSize) {
        int boundedBatch = Math.max(1, Math.min(100, batchSize));
        executions.finalizeExpiredCancellations(boundedBatch);
        List<AgentResult> results = new ArrayList<>();
        for (String executionId : executions.findRecoverable(boundedBatch)) {
            try { results.add(resume(executionId, ResumeReason.PROCESS_RECOVERY)); }
            catch (RuntimeException ignored) { }
        }
        return results;
    }

    public String ownerId() { return owner.value(); }

    private void metric(String name) {
        MeterRegistry registry = metrics;
        if (registry != null) registry.counter(name).increment();
    }

    private AgentState initial(AgentStartCommand command) {
        QaRequest request = command.request();
        AgentBudgetState budget = switch (command.mode()) {
            case "AGENTIC_RAG" -> AgentBudgetState.of(maxSteps, maxSearchActions, maxNavigationActions);
            case "TOOL_AGENT" -> new AgentBudgetState(maxSteps, 0, 0, maxSteps);
            default -> throw new IllegalArgumentException("unsupported durable agent mode");
        };
        Map<String, Object> toolState = new LinkedHashMap<>();
        toolState.put("requestId", TraceCorrelation.currentRequestId());
        toolState.put("traceId", UUID.randomUUID().toString());
        toolState.put("steps", List.of("PLAN"));
        if (!command.fallbackTool().isBlank()) toolState.put("fallbackTool", command.fallbackTool());
        toolState.put("parts", parts(request.query()));
        Instant deadline = Instant.now().plusMillis(deadlineMs);
        return new AgentState(AgentState.CURRENT_STATE_VERSION, command.executionId(), command.mode(), request.userId(),
                request.userRoles(), request.datasetId(), request.conversationId(), request.query(),
                AgentRuntimeStatus.RUNNING, 0, maxSteps, deadline, budget, List.of(), List.of(), List.of(),
                toolState, null, "", List.of(), null, 0);
    }

    private AgentResult run(AgentState state, boolean recovering,
            com.modelrag.agent.orchestrator.AgenticRetrievalEventSink events) {
        AgentModeHandler handler = handler(state.mode());
        AgentState current = state;
        while (true) {
            var row = executions.findById(current.executionId());
            if (row.isPresent() && ("CANCEL_REQUESTED".equals(row.get().status())
                    || "CANCELLED".equals(row.get().status()))) {
                return terminal(current, AgentRuntimeStatus.CANCELLED, "Agent 执行已取消。", "cancelled");
            }
            if (Instant.now().isAfter(current.deadlineAt())) {
                return terminal(current, AgentRuntimeStatus.TIMEOUT, "Agent 执行超时，已停止后续动作。", "deadline");
            }
            AgentPendingAction pending = current.pendingAction();
            if (pending == null) {
                AgentModeDecision decision;
                try {
                    AgentState decisionState = current;
                    decision = withLeaseHeartbeat(current.executionId(),
                            () -> handler.decide(decisionState));
                } catch (AgentCheckpointConflictException | AgentLeaseUnavailableException error) {
                    return running(current.executionId(), current.mode());
                } catch (RuntimeException error) {
                    if (cancelRequested(current.executionId())) {
                        current = current.toBuilder().status(AgentRuntimeStatus.CANCELLED).pendingAction(null)
                                .result(new AgentResultSnapshot("CANCELLED", "Agent 执行已取消。", List.of(), 0, true,
                                        string(current.toolState().get("traceId")),
                                        append(current.degradedComponents(), "cancelled"))).build();
                    } else {
                        current = current.toBuilder().status(AgentRuntimeStatus.ERROR)
                                .result(new AgentResultSnapshot("ERROR", "Agent 计划生成失败，请稍后重试。", List.of(), 0,
                                        true, string(current.toolState().get("traceId")),
                                        append(current.degradedComponents(), "agent-plan-error")))
                                .build();
                    }
                    return persistTerminal(current, events);
                }
                if (decision.terminal()) {
                    AgentResultSnapshot result = decision.terminalResult();
                    AgentRuntimeStatus status = "ERROR".equals(result.status()) ? AgentRuntimeStatus.ERROR
                            : AgentRuntimeStatus.DONE;
                    current = current.toBuilder().status(status).result(result).build();
                    return persistTerminal(current, events);
                }
                pending = decision.pendingAction();
                if (pending == null) throw new IllegalStateException("mode handler returned empty decision");
                current = current.toBuilder().pendingAction(pending).build();
                if (pending.requiresApproval() && current.approvalId().isBlank()) {
                    operationGuard.requireAvailableForSideEffect();
                    ApprovalRecord approval = approvals.request(current.executionId(), pending.actionName(),
                            write(pending.arguments()), current.userId(), current.datasetId(), current.conversationId());
                    current = current.toBuilder().status(AgentRuntimeStatus.WAITING_APPROVAL)
                            .approvalId(approval.id()).build();
                    current = checkpoint(current);
                    checkpoints.release(current.executionId(), owner.value());
                    return toResult(current);
                }
                publish(events, "PLAN", pending, current);
                current = checkpoint(current);
                publish(events, "ACT", pending, current);
                recovering = false;
            } else if (recovering && pending.kind() == AgentPendingActionKind.BUSINESS_TOOL
                    && !pending.toolIdempotent()) {
                current = current.toBuilder().status(AgentRuntimeStatus.RECONCILIATION_REQUIRED)
                        .pendingAction(null).result(new AgentResultSnapshot("RECONCILIATION_REQUIRED",
                                "非幂等工具调用结果未知，需要人工核对。", List.of(), 0, true,
                                string(current.toolState().get("traceId")), append(current.degradedComponents(),
                                        "NON_IDEMPOTENT_ACTION_UNCERTAIN"))).build();
                metric("modelrag.agent.reconciliation_required");
                return persistTerminal(current, events);
            }
            try {
                AgentState actionState = current;
                AgentPendingAction action = pending;
                AgentState outcome = withLeaseHeartbeat(current.executionId(),
                        () -> handler.execute(actionState, action));
                if (cancelRequested(current.executionId())) {
                    current = current.toBuilder().status(AgentRuntimeStatus.CANCELLED).pendingAction(null)
                            .result(new AgentResultSnapshot("CANCELLED", "Agent 执行已取消。", List.of(), 0, true,
                                    string(current.toolState().get("traceId")),
                                    append(current.degradedComponents(), "cancelled"))).build();
                    return persistTerminal(current, events);
                }
                publish(events, "OBSERVE", pending, outcome);
                current = checkpoint(outcome);
                recovering = false;
                if (terminal(current.status().name())) {
                    checkpoints.release(current.executionId(), owner.value());
                    publish(events, "DONE", null, current);
                    return toResult(current);
                }
            } catch (AgentCheckpointConflictException | AgentLeaseUnavailableException error) {
                return running(current.executionId(), current.mode());
            } catch (RuntimeException error) {
                if (cancelRequested(current.executionId())) {
                    current = current.toBuilder().status(AgentRuntimeStatus.CANCELLED).pendingAction(null)
                            .result(new AgentResultSnapshot("CANCELLED", "Agent 执行已取消。", List.of(), 0, true,
                                    string(current.toolState().get("traceId")),
                                    append(current.degradedComponents(), "cancelled"))).build();
                } else if (pending.kind() == AgentPendingActionKind.BUSINESS_TOOL && !pending.toolIdempotent()) {
                    current = current.toBuilder().status(AgentRuntimeStatus.RECONCILIATION_REQUIRED)
                            .pendingAction(null).result(new AgentResultSnapshot("RECONCILIATION_REQUIRED",
                                    "非幂等工具调用结果未知，需要人工核对。", List.of(), 0, true,
                                    string(current.toolState().get("traceId")), append(current.degradedComponents(),
                                            "NON_IDEMPOTENT_ACTION_UNCERTAIN"))).build();
                    metric("modelrag.agent.reconciliation_required");
                } else {
                    current = current.toBuilder().status(AgentRuntimeStatus.ERROR).pendingAction(null)
                            .result(new AgentResultSnapshot("ERROR", "Agent 执行失败，请稍后重试。", List.of(), 0, true,
                                    string(current.toolState().get("traceId")), append(current.degradedComponents(),
                                            "agent-runtime-error"))).build();
                }
                return persistTerminal(current, events);
            }
        }
    }

    private AgentResult persistTerminal(AgentState state,
            com.modelrag.agent.orchestrator.AgenticRetrievalEventSink events) {
        AgentState saved;
        try {
            saved = checkpoint(state);
        } catch (AgentCheckpointConflictException | AgentLeaseUnavailableException error) {
            return running(state.executionId(), state.mode());
        }
        checkpoints.release(saved.executionId(), owner.value());
        publish(events, "DONE", null, saved);
        return toResult(saved);
    }

    private AgentResult terminal(AgentState state, AgentRuntimeStatus status, String answer, String degraded) {
        AgentState next = state.toBuilder().status(status).pendingAction(null)
                .result(new AgentResultSnapshot(status.name(), answer, List.of(), 0, true,
                        string(state.toolState().get("traceId")), append(state.degradedComponents(), degraded))).build();
        return persistTerminal(next, com.modelrag.agent.orchestrator.AgenticRetrievalEventSink.NOOP);
    }

    private AgentState checkpoint(AgentState state) {
        AgentState saved = checkpoints.checkpoint(state, owner.value());
        recordCheckpoint(saved);
        return saved;
    }

    private <T> T withLeaseHeartbeat(String executionId, Supplier<T> action) {
        renewForAction(executionId);
        long periodMillis = Math.max(100, checkpoints.lease().toMillis() / 3);
        AtomicBoolean leaseLost = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        ScheduledFuture<?> heartbeat = LEASE_HEARTBEATS.scheduleAtFixedRate(() -> {
            try {
                if (!checkpoints.renew(executionId, owner.value())) {
                    leaseLost.set(true);
                    caller.interrupt();
                }
            } catch (RuntimeException error) {
                leaseLost.set(true);
                caller.interrupt();
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        try {
            T value = action.get();
            if (leaseLost.get()) throw new AgentLeaseUnavailableException("agent execution lease is no longer owned");
            return value;
        } catch (RuntimeException error) {
            if (leaseLost.get()) throw new AgentLeaseUnavailableException("agent execution lease is no longer owned");
            throw error;
        } finally {
            heartbeat.cancel(false);
            if (leaseLost.get()) Thread.interrupted();
        }
    }

    private void renewForAction(String executionId) {
        try {
            if (!checkpoints.renew(executionId, owner.value())) {
                throw new AgentLeaseUnavailableException("agent execution lease is no longer owned");
            }
        } catch (AgentLeaseUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AgentLeaseUnavailableException("agent execution lease cannot be renewed");
        }
    }

    private void recordCheckpoint(AgentState state) {
        recordTrace(state.executionId(), "CHECKPOINT", Map.of(
                "checkpointSeq", state.checkpointSeq(),
                "status", state.status().name(),
                "currentStep", state.currentStep()));
    }

    private void recordResume(String executionId, AgentState state, ResumeReason reason) {
        recordTrace(executionId, "RESUME", Map.of(
                "checkpointSeq", state.checkpointSeq(),
                "reason", reason == null ? ResumeReason.CLIENT_RETRY.name() : reason.name()));
    }

    private void recordTrace(String executionId, String phase, Map<String, Object> data) {
        if (stepTracer == null) return;
        try {
            stepTracer.record(executionId, phase, "Durable agent runtime " + phase, data, 0);
        } catch (RuntimeException ignored) { }
    }

    private AgentModeHandler handler(String mode) {
        return handlers.stream().filter(value -> value.mode().equals(mode)).findFirst()
                .orElseThrow(() -> new IllegalStateException("agent mode handler unavailable: " + mode));
    }

    private void verifyScope(com.modelrag.agent.runtime.repository.AgentExecutionRecord existing, QaRequest request) {
        if (!existing.userId().equals(request.userId()) || existing.datasetId() != request.datasetId()
                || !Objects.equals(existing.conversationId(), request.conversationId())) {
            throw new IllegalArgumentException("execution scope does not match request");
        }
    }

    private AgentResult resultFrom(com.modelrag.agent.runtime.repository.AgentExecutionRecord row, AgentState state) {
        if ("CANCEL_REQUESTED".equals(row.status()) || "CANCELLED".equals(row.status())) {
            return cancelled(row.executionId(), row.mode());
        }
        if (state != null && state.result() != null) return toResult(state);
        if (state != null && "WAITING_APPROVAL".equals(row.status())) return toResult(state);
        if (terminal(row.status())) return statusResult(row.executionId(), row.mode(), row.status());
        return running(row.executionId(), row.mode());
    }

    private AgentResult running(String executionId, String mode) {
        RouteDecision route = route(mode);
        return new AgentResult(executionId, route, "RUNNING", null, null, List.of("RUNNING"),
                List.of(), 0, false, executionId, List.of("already-running"));
    }

    private AgentResult cancelled(String executionId, String mode) {
        return new AgentResult(executionId, route(mode), "CANCELLED", "Agent 执行已取消。", null,
                List.of("CANCELLED"), List.of(), 0, true, executionId, List.of("cancelled"));
    }

    private AgentResult statusResult(String executionId, String mode, String status) {
        return new AgentResult(executionId, route(mode), status, null, null,
                List.of(status), List.of(), 0, true, executionId, List.of(status.toLowerCase()));
    }

    private AgentResult toResult(AgentState state) {
        AgentResultSnapshot snapshot = state.result();
        List<Citation> citations = snapshot == null ? List.of() : snapshot.citations().stream()
                .map(value -> new Citation(0, value.documentId(), "", "", 0, value.excerpt(), value.score(),
                        state.executionId(), value.documentVersionId(), value.nodeId(), null, null, "", null, null)).toList();
        List<String> steps = state.toolState().get("steps") instanceof List<?> values
                ? values.stream().filter(Objects::nonNull).map(String::valueOf).toList() : List.of();
        if (steps.isEmpty()) steps = List.of(state.status().name());
        return new AgentResult(state.executionId(), route(state.mode()), state.status().name(),
                snapshot == null ? null : snapshot.answer(), state.approvalId().isBlank() ? null : state.approvalId(),
                steps, citations, snapshot == null ? 0 : snapshot.confidence(),
                snapshot != null && snapshot.refused(), snapshot == null ? state.executionId() : snapshot.traceId(),
                snapshot == null ? state.degradedComponents() : snapshot.degradedComponents());
    }

    private RouteDecision route(String mode) {
        try { return RouteDecision.valueOf(mode); } catch (RuntimeException error) { return RouteDecision.AGENTIC_RAG; }
    }

    private boolean terminal(String status) {
        return Set.of("DONE", "ERROR", "TIMEOUT", "CANCELLED", "RECONCILIATION_REQUIRED").contains(status);
    }

    private boolean cancelRequested(String executionId) {
        return executions.findById(executionId).map(row -> "CANCEL_REQUESTED".equals(row.status())
                || "CANCELLED".equals(row.status())).orElse(false);
    }

    private void publish(com.modelrag.agent.orchestrator.AgenticRetrievalEventSink events, String type,
            AgentPendingAction action, AgentState state) {
        if (events == null) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("step", state.currentStep());
            data.put("status", state.status().name());
            if (action != null) data.put("action", action.actionName());
            events.publish(type, "Agent durable runtime " + type, Map.copyOf(data));
        } catch (RuntimeException ignored) { }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception error) { throw new IllegalArgumentException("agent action cannot be serialized", error); }
    }

    private static List<String> parts(String value) {
        String[] values = (value == null ? "" : value).split("并且|然后|同时|以及|；|;");
        List<String> result = new ArrayList<>();
        for (String item : values) if (!item.isBlank()) result.add(item.trim());
        return result.isEmpty() ? List.of(value == null ? "" : value) : result.stream().limit(10).toList();
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values == null ? List.of() : values);
        if (value != null && !value.isBlank()) result.add(value);
        return result.stream().distinct().limit(16).toList();
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
