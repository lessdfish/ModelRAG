package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.orchestrator.AgentExecutionRegistry;
import com.modelrag.agent.orchestrator.AgentPlanner;
import com.modelrag.agent.orchestrator.AgentResult;
import com.modelrag.agent.policy.AgentDecisionValidator;
import com.modelrag.agent.policy.LlmAgentPolicy;
import com.modelrag.agent.retrieval.RetrievalActionName;
import com.modelrag.agent.retrieval.RetrievalActionRegistry;
import com.modelrag.agent.runtime.AgentBudgetState;
import com.modelrag.agent.runtime.AgentCheckpointService;
import com.modelrag.agent.runtime.AgentEvidenceSnapshot;
import com.modelrag.agent.runtime.AgentModeDecision;
import com.modelrag.agent.runtime.AgentModeHandler;
import com.modelrag.agent.runtime.AgentPendingAction;
import com.modelrag.agent.runtime.AgentPendingActionKind;
import com.modelrag.agent.runtime.AgentResultSnapshot;
import com.modelrag.agent.runtime.AgentRuntime;
import com.modelrag.agent.runtime.AgentRuntimeStatus;
import com.modelrag.agent.runtime.AgentStartCommand;
import com.modelrag.agent.runtime.AgentState;
import com.modelrag.agent.runtime.AgentStateCodec;
import com.modelrag.agent.runtime.ResumeReason;
import com.modelrag.agent.runtime.AgenticRagModeHandler;
import com.modelrag.agent.runtime.ToolAgentModeHandler;
import com.modelrag.agent.runtime.repository.AgentCheckpointRecord;
import com.modelrag.agent.runtime.repository.AgentCheckpointRepository;
import com.modelrag.agent.runtime.repository.AgentExecutionRecord;
import com.modelrag.agent.runtime.repository.AgentExecutionRepository;
import com.modelrag.agent.safety.LoopDetector;
import com.modelrag.agent.trace.AgentStepTracer;
import com.modelrag.toolgateway.catalog.ToolCatalog;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.catalog.ToolRegistrationCommand;
import com.modelrag.toolgateway.coordination.ToolCoordinationStore;
import com.modelrag.toolgateway.execution.InternalToolHandlerRegistry;
import com.modelrag.toolgateway.execution.ResilientToolExecutor;
import com.modelrag.toolgateway.execution.ToolDispatcher;
import com.modelrag.toolgateway.execution.ToolExecutionCanceller;
import com.modelrag.toolgateway.execution.ToolGateway;
import com.modelrag.toolgateway.http.HttpToolInvoker;
import com.modelrag.toolgateway.policy.ToolAccessPolicy;
import com.modelrag.toolgateway.policy.ToolRiskPolicy;
import com.modelrag.toolgateway.security.ToolCallValidator;
import com.modelrag.toolgateway.trace.ToolCallTracer;
import com.modelrag.api.UserModelProvider;
import com.modelrag.common.operation.OperationGuard;
import com.modelrag.common.security.RequestUser;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.evidence.AnswerSynthesizer;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceSelector;
import com.modelrag.qa.evidence.EvidenceSufficiencyPolicy;
import com.modelrag.qa.orchestrator.ContextAssembler;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class G81DurableAgentRuntimeStabilizationTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void policyEvidenceSurvivesCheckpointReloadAndRemainsAuthoritative() {
        List<String> prompts = new ArrayList<>();
        UserModelProvider provider = new UserModelProvider() {
            @Override public String generate(String userId, String prompt) {
                prompts.add(prompt);
                return "{\"type\":\"FINISH\"}";
            }
            @Override public boolean configured(String userId) { return true; }
        };
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("g81UserModel", provider);
        LlmAgentPolicy policy = new LlmAgentPolicy(json,
                beans.getBeanProvider(UserModelProvider.class), new AgentDecisionValidator(),
                12_000, new SimpleMeterRegistry());
        AgenticRagModeHandler handler = new AgenticRagModeHandler(
                mock(RetrievalActionRegistry.class), policy, new AgentDecisionValidator(),
                new EvidenceSelector(16), new EvidenceSufficiencyPolicy(), mock(AnswerSynthesizer.class),
                mock(ContextAssembler.class), new SimpleMeterRegistry(), 20);
        AgentEvidenceSnapshot snapshot = new AgentEvidenceSnapshot("evidence-1", 7, 23, 29, 55,
                101L, 31L, "leave.docx", "RETRIEVAL", "PARAGRAPH", "PARAGRAPH", "人事 > 年假",
                "员工年假为 10 天", "人事 > 年假", .95, "SEMANTIC", true);
        AgentState state = AgentState.initial("evidence-exec", "AGENTIC_RAG", "user", 7, null,
                "年假是多少", 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2))
                .toBuilder().observedSources(List.of(new com.modelrag.agent.runtime.AgentObservedSource(
                        55, 23, 29, 31L, "leave.docx", "人事 > 年假", "PARAGRAPH", .95)))
                .evidence(List.of(snapshot)).build();
        AgentStateCodec codec = new AgentStateCodec(json, 100_000);

        handler.decide(state);
        String before = prompts.get(0);
        AgentState reloaded = codec.decode(codec.encode(state));
        handler.decide(reloaded);
        String after = prompts.get(1);

        assertEquals(before, after);
        assertTrue(after.contains("currentEvidence"));
        assertTrue(after.contains("员工年假为 10 天"));
        assertTrue(after.contains("node=55"));
        assertTrue(after.contains("document=23"));
        assertTrue(after.contains("documentVersion=29"));
    }

    @Test
    void liveLeaseCancellationStaysRequestedUntilTheOwnerObservesIt() throws Exception {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(store);
        BlockingHandler handler = new BlockingHandler();
        AgentRuntime owner = runtime(checkpoints, store, handler, mock(ApprovalGate.class));
        AtomicReference<AgentResult> result = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> result.set(owner.start(new AgentStartCommand(
                new QaRequest(7, "question", null, "user", Set.of()), "cancel-exec", "AGENTIC_RAG", ""))));
        try {
            assertTrue(handler.started.await(2, TimeUnit.SECONDS));
            AgentExecutionRegistry canceler = registry(store, "cancel-exec");

            canceler.requestCancel(new RequestUser("user", Set.of(), Set.of()), "cancel-exec");

            assertEquals("CANCEL_REQUESTED", store.status("cancel-exec"));
            handler.release.countDown();
            assertTrue(handler.finished.await(2, TimeUnit.SECONDS));
            worker.join(2_000);
            assertEquals("CANCELLED", result.get().status());
            assertEquals("CANCELLED", store.status("cancel-exec"));
        } finally {
            handler.release.countDown();
            worker.join(2_000);
        }
    }

    @Test
    void unownedCancellationFinalizesAndCancelledApprovalCannotResume() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentExecutionRegistry canceler = registry(store, "unowned-cancel");
        store.create(AgentState.initial("unowned-cancel", "AGENTIC_RAG", "user", 7, null,
                "question", 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2)));

        canceler.requestCancel(new RequestUser("user", Set.of(), Set.of()), "unowned-cancel");

        assertEquals("CANCELLED", store.status("unowned-cancel"));

        Store approvalStore = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(approvalStore);
        ApprovalGate approvals = mock(ApprovalGate.class);
        CountingHandler handler = new CountingHandler();
        AgentRuntime runtime = runtime(checkpoints, approvalStore, handler, approvals);
        AgentState waiting = AgentState.initial("approval-cancel", "AGENTIC_RAG", "user", 7, null,
                "change", 4, Instant.now().plusSeconds(30), new AgentBudgetState(4, 0, 0, 4))
                .toBuilder().status(AgentRuntimeStatus.WAITING_APPROVAL)
                .pendingAction(new AgentPendingAction("approval-cancel:action:1", AgentPendingActionKind.BUSINESS_TOOL,
                        "change_order", Map.of("query", "change"), "approval-cancel:action:1", true, false,
                        Instant.now())).approvalId("approval-cancel-1").build();
        approvalStore.create(waiting.toBuilder().status(AgentRuntimeStatus.RUNNING).build());
        approvalStore.tryClaim("approval-cancel", runtime.ownerId(), Duration.ofSeconds(30));
        checkpoints.checkpoint(waiting, runtime.ownerId());
        AgentExecutionRegistry approvalCanceler = registry(approvalStore, "approval-cancel");
        approvalCanceler.requestCancel(new RequestUser("user", Set.of(), Set.of()), "approval-cancel");
        when(approvals.get("approval-cancel-1")).thenReturn(Optional.of(new ApprovalRecord(
                "approval-cancel-1", "approval-cancel", "change_order", "{}", "APPROVED", "approver",
                Instant.now().plusSeconds(60), "user", 7L, null)));

        AgentResult resumed = runtime.resumeAfterApproval("approval-cancel-1");

        assertEquals("CANCELLED", resumed.status());
        assertEquals(0, handler.executions);
    }

    @Test
    void idempotentHttpReplayUsesTheExactPersistedActionKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/idempotent", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/idempotent";
            TestPersistenceConfiguration.TestToolCatalog tools = new TestPersistenceConfiguration.TestToolCatalog();
            tools.register(new ToolRegistrationCommand("idempotent-http", "safe read", "LOW", true,
                    "HTTP", endpoint, null, null, "{}", Set.of(), Set.of(), true));
            AgentPlanner planner = mock(AgentPlanner.class);
            when(planner.reactStep(anyString(), anyString(), anyList(), eq("idempotent-http"), anyList(),
                    anyList(), anyInt())).thenReturn(
                            new AgentPlanner.Plan("idempotent-http", List.of("ping"), "test"),
                            new AgentPlanner.Plan("idempotent-http", List.of(), "test"));
            ResilientToolExecutor executor = new ResilientToolExecutor(new ToolCallValidator(json), coordination());
            HttpToolInvoker http = new HttpToolInvoker(json, 2_000, true, "");
            ToolGateway gateway = new ToolGateway(tools, new ToolAccessPolicy(), executor,
                    new ToolDispatcher(http, new InternalToolHandlerRegistry(List.of())), mock(ToolCallTracer.class));
            ToolAgentModeHandler handler = new ToolAgentModeHandler(planner, tools, new ToolRiskPolicy(),
                    new ToolAccessPolicy(), gateway, mock(LoopDetector.class), json, (OperationGuard) () -> { });

            Store store = new Store(new AgentStateCodec(json, 100_000));
            store.failSaveAt = 3; // initial, pending, then crash after the first HTTP 200
            AgentRuntime first = runtime(checkpoints(store), store, handler, mock(ApprovalGate.class));
            AgentResult crashed = first.start(new AgentStartCommand(
                    new QaRequest(7, "ping", null, "user", Set.of()), "http-exec", "TOOL_AGENT",
                    "idempotent-http"));
            assertEquals("RUNNING", crashed.status());
            store.expireLease("http-exec");

            AgentRuntime replay = runtime(checkpoints(store), store, handler, mock(ApprovalGate.class));
            AgentResult completed = replay.resume("http-exec", ResumeReason.PROCESS_RECOVERY);

            assertEquals("DONE", completed.status());
            assertEquals(List.of("http-exec:action:1", "http-exec:action:1"), keys);
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void realToolAgentNonIdempotentRecoveryNeverInvokesTheExternalTool() {
        ToolDescriptor definition = new ToolDescriptor("non-idempotent-http", "unsafe write", "HIGH", true,
                "HTTP", "https://example.invalid/write", null, "{}", Set.of(), Set.of(), false, false);
        ToolCatalog tools = mock(ToolCatalog.class);
        when(tools.get("non-idempotent-http")).thenReturn(definition);
        ToolGateway gateway = mock(ToolGateway.class);
        ToolAgentModeHandler handler = new ToolAgentModeHandler(mock(AgentPlanner.class), tools,
                new ToolRiskPolicy(), new ToolAccessPolicy(), gateway, mock(LoopDetector.class), json,
                (OperationGuard) () -> { });
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(store);
        AgentRuntime runtime = runtime(checkpoints, store, handler, mock(ApprovalGate.class));
        AgentState pending = AgentState.initial("non-idempotent-exec", "TOOL_AGENT", "user", 7, null,
                "change", 4, Instant.now().plusSeconds(30), new AgentBudgetState(4, 0, 0, 4)).toBuilder()
                .pendingAction(new AgentPendingAction("non-idempotent-exec:action:1",
                        AgentPendingActionKind.BUSINESS_TOOL, definition.name(), Map.of("query", "change"),
                        "non-idempotent-exec:action:1", false, false, Instant.now())).build();
        store.create(pending);
        store.tryClaim(pending.executionId(), runtime.ownerId(), Duration.ofSeconds(30));
        checkpoints.checkpoint(pending, runtime.ownerId());

        AgentResult result = runtime.resume(pending.executionId(), ResumeReason.PROCESS_RECOVERY);

        assertEquals("RECONCILIATION_REQUIRED", result.status());
        assertTrue(result.degradedComponents().contains("NON_IDEMPOTENT_ACTION_UNCERTAIN"));
        verify(tools, never()).get("non-idempotent-http");
        verifyNoInteractions(gateway);
    }

    @Test
    void absoluteDeadlineSurvivesRestartAndPreventsAnotherAction() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(store);
        CountingHandler handler = new CountingHandler();
        AgentRuntime runtime = runtime(checkpoints, store, handler, mock(ApprovalGate.class));
        AgentState expired = AgentState.initial("expired-exec", "AGENTIC_RAG", "user", 7, null,
                "question", 4, Instant.now().minusSeconds(1), AgentBudgetState.of(4, 2, 2)).toBuilder()
                .pendingAction(new AgentPendingAction("expired-exec:action:1", AgentPendingActionKind.RETRIEVAL,
                        RetrievalActionName.SEARCH_KNOWLEDGE.name(), Map.of("query", "question"),
                        "expired-exec:action:1", false, true, Instant.now())).build();
        store.create(expired);
        store.tryClaim(expired.executionId(), runtime.ownerId(), Duration.ofSeconds(30));
        checkpoints.checkpoint(expired, runtime.ownerId());

        AgentResult result = runtime.resume(expired.executionId(), ResumeReason.PROCESS_RECOVERY);

        assertEquals("TIMEOUT", result.status());
        assertEquals(0, handler.executions);
    }

    @Test
    void remainingBudgetsSurviveCheckpointReload() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(store);
        AgentState state = AgentState.initial("budget-exec", "AGENTIC_RAG", "user", 7, null,
                "question", 6, Instant.now().plusSeconds(30), new AgentBudgetState(2, 1, 3, 2));
        store.create(state);
        store.tryClaim(state.executionId(), "owner-a", Duration.ofSeconds(30));
        AgentState saved = checkpoints.checkpoint(state, "owner-a");
        AgentState reloaded = checkpoints.loadLatest(state.executionId()).orElseThrow();

        assertEquals(saved.budgets(), reloaded.budgets());
        assertEquals(2, reloaded.budgets().remainingSteps());
        assertEquals(1, reloaded.budgets().remainingSearchActions());
        assertEquals(3, reloaded.budgets().remainingNavigationActions());
    }

    @Test
    void liveLeaseCannotBeStolenButExpiredLeaseCanBeTakenOver() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentState state = AgentState.initial("lease-exec", "AGENTIC_RAG", "user", 7, null,
                "question", 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2));
        store.create(state);

        assertTrue(store.tryClaim("lease-exec", "owner-a", Duration.ofSeconds(30)));
        assertFalse(store.tryClaim("lease-exec", "owner-b", Duration.ofSeconds(30)));
        store.expireLease("lease-exec");
        assertTrue(store.tryClaim("lease-exec", "owner-b", Duration.ofSeconds(30)));
    }

    @Test
    void duplicateExecutionIdDoesNotResetStateAndTerminalRetryUsesPersistedResult() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(store);
        AgentState waiting = AgentState.initial("duplicate-exec", "AGENTIC_RAG", "user", 7, null,
                "question", 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2)).toBuilder()
                .status(AgentRuntimeStatus.WAITING_APPROVAL).approvalId("approval-1").build();
        store.create(waiting.toBuilder().status(AgentRuntimeStatus.RUNNING).build());
        store.tryClaim("duplicate-exec", "owner-a", Duration.ofSeconds(30));
        AgentState savedWaiting = checkpoints.checkpoint(waiting, "owner-a");
        store.create(AgentState.initial("duplicate-exec", "AGENTIC_RAG", "other", 9, null,
                "reset", 9, Instant.now().plusSeconds(300), AgentBudgetState.of(9, 9, 9)));
        AgentExecutionRecord duplicate = store.findById("duplicate-exec").orElseThrow();
        assertEquals("WAITING_APPROVAL", duplicate.status());
        assertEquals(savedWaiting.checkpointSeq(), duplicate.lastCheckpointSeq());
        assertEquals("approval-1", store.latestState("duplicate-exec").approvalId());

        AgentState done = AgentState.initial("terminal-exec", "AGENTIC_RAG", "user", 7, null,
                "question", 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2)).toBuilder()
                .status(AgentRuntimeStatus.DONE)
                .result(new AgentResultSnapshot("DONE", "persisted answer", List.of(), 1, false,
                        "terminal-exec", List.of())).build();
        store.create(done.toBuilder().status(AgentRuntimeStatus.RUNNING).build());
        store.tryClaim("terminal-exec", "owner-a", Duration.ofSeconds(30));
        checkpoints.checkpoint(done, "owner-a");
        AgentRuntime retry = runtime(checkpoints, store, new CountingHandler(), mock(ApprovalGate.class));

        AgentResult result = retry.start(new AgentStartCommand(
                new QaRequest(7, "new request is ignored", null, "user", Set.of()),
                "terminal-exec", "AGENTIC_RAG", ""));

        assertEquals("DONE", result.status());
        assertEquals("persisted answer", result.answer());
    }

    @Test
    void recoveryBatchIsBounded() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(store);
        CountingHandler handler = new CountingHandler();
        AgentRuntime runtime = runtime(checkpoints, store, handler, mock(ApprovalGate.class));
        store.recoverableIds = List.of("recover-1", "recover-2", "recover-3");
        for (String id : store.recoverableIds) {
            AgentState pending = AgentState.initial(id, "AGENTIC_RAG", "user", 7, null,
                    "question", 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2))
                    .toBuilder().pendingAction(new AgentPendingAction(id + ":action:1",
                            AgentPendingActionKind.RETRIEVAL, RetrievalActionName.SEARCH_KNOWLEDGE.name(),
                            Map.of("query", "question"), id + ":action:1", false, true, Instant.now())).build();
            store.create(pending);
            store.tryClaim(id, "seed", Duration.ofSeconds(30));
            checkpoints.checkpoint(pending, "seed");
            store.expireLease(id);
        }

        List<AgentResult> results = runtime.recoverBatch(2);

        assertEquals(2, store.lastRecoveryLimit);
        assertEquals(2, results.size());
        assertEquals(2, handler.executions);
    }

    @Test
    void resumeAndCheckpointWriteSafeDurableTraceMetadata() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService checkpoints = checkpoints(store);
        AgentStepTracer traces = mock(AgentStepTracer.class);
        CountingHandler handler = new CountingHandler();
        AgentState pending = AgentState.initial("trace-exec", "AGENTIC_RAG", "user", 7, null,
                "question", 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2))
                .toBuilder().pendingAction(new AgentPendingAction("trace-exec:action:1",
                        AgentPendingActionKind.RETRIEVAL, RetrievalActionName.SEARCH_KNOWLEDGE.name(),
                        Map.of("query", "question"), "trace-exec:action:1", false, true, Instant.now())).build();
        store.create(pending);
        store.tryClaim("trace-exec", "seed", Duration.ofSeconds(30));
        checkpoints.checkpoint(pending, "seed");
        store.expireLease("trace-exec");
        AgentRuntime runtime = runtime(checkpoints, store, handler, mock(ApprovalGate.class), traces);

        runtime.resume("trace-exec", ResumeReason.PROCESS_RECOVERY);

        verify(traces).record(eq("trace-exec"), eq("RESUME"), anyString(), anyMap(), anyLong());
        verify(traces, org.mockito.Mockito.atLeastOnce()).record(eq("trace-exec"), eq("CHECKPOINT"),
                anyString(), anyMap(), anyLong());
    }

    private AgentExecutionRegistry registry(Store store, String executionId) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<AgentExecutionRegistry.Scope>>any(), eq(executionId)))
                .thenReturn(List.of(new AgentExecutionRegistry.Scope("user", 7, null)));
        return new AgentExecutionRegistry(jdbc, mock(ToolExecutionCanceller.class),
                mock(com.modelrag.api.ModelInvocationCanceller.class), store);
    }

    private AgentCheckpointService checkpoints(Store store) {
        return new AgentCheckpointService(store, store, new AgentStateCodec(json, 100_000), 30);
    }

    private AgentRuntime runtime(AgentCheckpointService checkpoints, Store store, AgentModeHandler handler,
            ApprovalGate approvals) {
        return runtime(checkpoints, store, handler, approvals, null);
    }

    private AgentRuntime runtime(AgentCheckpointService checkpoints, Store store, AgentModeHandler handler,
            ApprovalGate approvals, AgentStepTracer traces) {
        return new AgentRuntime(checkpoints, store, List.of(handler), approvals, (OperationGuard) () -> { }, json,
                4, 5_000, 4, 12, traces);
    }

    private ToolCoordinationStore coordination() {
        return new ToolCoordinationStore() {
            @Override public void ensureAvailable() { }
            @Override public String circuit(String toolName) { return null; }
            @Override public void saveCircuit(String toolName, String state, int failures,
                    long openedUntil, Duration ttl) { }
            @Override public long incrementRate(String toolName, Duration window) { return 1; }
        };
    }

    private static class CountingHandler implements AgentModeHandler {
        int executions;

        @Override public String mode() { return "AGENTIC_RAG"; }

        @Override public AgentModeDecision decide(AgentState state) {
            return AgentModeDecision.terminal(new AgentResultSnapshot("DONE", "finished", List.of(), 1, false,
                    state.executionId(), List.of()));
        }

        @Override public AgentState execute(AgentState state, AgentPendingAction action) {
            executions++;
            return state.toBuilder().currentStep(state.currentStep() + 1)
                    .budgets(state.budgets().consumeStep()).pendingAction(null).build();
        }
    }

    private static final class BlockingHandler extends CountingHandler {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override public AgentModeDecision decide(AgentState state) {
            if (state.pendingAction() == null) {
                return AgentModeDecision.action(new AgentPendingAction(state.executionId() + ":action:1",
                        AgentPendingActionKind.RETRIEVAL, RetrievalActionName.SEARCH_KNOWLEDGE.name(),
                        Map.of("query", state.goal()), state.executionId() + ":action:1", false, true,
                        Instant.now()));
            }
            return super.decide(state);
        }

        @Override public AgentState execute(AgentState state, AgentPendingAction action) {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
            return super.execute(state, action);
        }
    }

    private static final class Store implements AgentExecutionRepository, AgentCheckpointRepository {
        private final AgentStateCodec codec;
        private final Map<String, AgentExecutionRecord> rows = new ConcurrentHashMap<>();
        private final Map<String, AgentCheckpointRecord> latest = new ConcurrentHashMap<>();
        private final List<AgentState> writes = new ArrayList<>();
        private List<String> recoverableIds = List.of();
        private int lastRecoveryLimit;
        private int saveCalls;
        private int failSaveAt = -1;

        private Store(AgentStateCodec codec) { this.codec = codec; }

        @Override public synchronized void create(AgentState state) {
            rows.putIfAbsent(state.executionId(), row(state, 0, null));
        }

        @Override public synchronized Optional<AgentExecutionRecord> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override public synchronized boolean tryClaim(String id, String owner, Duration lease) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !"RUNNING".equals(row.status())) return false;
            boolean live = row.leaseOwner() != null && row.leaseUntil() != null
                    && row.leaseUntil().isAfter(Instant.now());
            if (live && !owner.equals(row.leaseOwner())) return false;
            rows.put(id, withLease(row, owner, Instant.now().plus(lease)));
            return true;
        }

        @Override public synchronized boolean tryClaimWaiting(String id, String owner, Duration lease) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !"WAITING_APPROVAL".equals(row.status()) || row.leaseOwner() != null) return false;
            rows.put(id, new AgentExecutionRecord(row.executionId(), row.userId(), row.datasetId(), row.conversationId(),
                    row.mode(), row.goal(), "RUNNING", row.currentStep(), row.maxSteps(), row.deadlineAt(),
                    row.stateVersion(), row.lastCheckpointSeq(), owner, Instant.now().plus(lease), row.errorCode(),
                    row.errorMessage(), row.finishedAt()));
            return true;
        }

        @Override public synchronized boolean renew(String id, String owner, Duration lease) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !owner.equals(row.leaseOwner())) return false;
            rows.put(id, withLease(row, owner, Instant.now().plus(lease)));
            return true;
        }

        @Override public synchronized boolean release(String id, String owner) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !owner.equals(row.leaseOwner())) return false;
            rows.put(id, withLease(row, null, null));
            return true;
        }

        @Override public synchronized boolean requestCancellation(String id) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !Set.of("RUNNING", "WAITING_APPROVAL", "CANCEL_REQUESTED").contains(row.status())) {
                return false;
            }
            rows.put(id, withStatus(row, "CANCEL_REQUESTED"));
            return true;
        }

        @Override public synchronized boolean finalizeCancellationIfUnowned(String id) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !"CANCEL_REQUESTED".equals(row.status()) || live(row)) return false;
            rows.put(id, new AgentExecutionRecord(row.executionId(), row.userId(), row.datasetId(), row.conversationId(),
                    row.mode(), row.goal(), "CANCELLED", row.currentStep(), row.maxSteps(), row.deadlineAt(),
                    row.stateVersion(), row.lastCheckpointSeq(), null, null, "CANCELLED",
                    "Agent execution cancelled", Instant.now()));
            return true;
        }

        @Override public synchronized int finalizeExpiredCancellations(int limit) {
            int count = 0;
            for (String id : new ArrayList<>(rows.keySet())) {
                if (count >= Math.max(1, Math.min(100, limit))) break;
                if (finalizeCancellationIfUnowned(id)) count++;
            }
            return count;
        }

        @Override public synchronized List<String> findRecoverable(int limit) {
            lastRecoveryLimit = limit;
            return recoverableIds.stream().limit(Math.max(1, Math.min(100, limit))).toList();
        }

        @Override public synchronized boolean save(AgentState state, String stateJson, String owner) {
            AgentExecutionRecord row = rows.get(state.executionId());
            if (row == null || row.lastCheckpointSeq() != state.checkpointSeq()
                    || !owner.equals(row.leaseOwner())) return false;
            if (("CANCEL_REQUESTED".equals(row.status()) || "CANCELLED".equals(row.status()))
                    && !"CANCELLED".equals(state.status().name())) return false;
            if (++saveCalls == failSaveAt) return false;
            AgentState decoded = codec.decode(stateJson);
            long next = state.checkpointSeq() + 1;
            latest.put(state.executionId(), new AgentCheckpointRecord(state.executionId(), next,
                    decoded.stateVersion(), decoded.status().name(), stateJson, Instant.now()));
            writes.add(decoded);
            String leaseOwner = terminal(decoded.status().name()) || "WAITING_APPROVAL".equals(decoded.status().name())
                    ? null : owner;
            rows.put(state.executionId(), new AgentExecutionRecord(row.executionId(), row.userId(), row.datasetId(),
                    row.conversationId(), decoded.mode(), decoded.goal(), decoded.status().name(), decoded.currentStep(),
                    decoded.maxSteps(), decoded.deadlineAt(), decoded.stateVersion(), next, leaseOwner,
                    leaseOwner == null ? null : Instant.now().plusSeconds(30), errorCode(decoded), errorMessage(decoded),
                    terminal(decoded.status().name()) ? Instant.now() : null));
            return true;
        }

        @Override public synchronized Optional<AgentCheckpointRecord> latest(String executionId) {
            return Optional.ofNullable(latest.get(executionId));
        }

        private String status(String id) { return rows.get(id).status(); }

        private AgentState latestState(String id) { return codec.decode(latest.get(id).stateJson()); }

        private void expireLease(String id) {
            AgentExecutionRecord row = rows.get(id);
            rows.put(id, withLease(row, row.leaseOwner(), Instant.now().minusSeconds(1)));
        }

        private boolean live(AgentExecutionRecord row) {
            return row.leaseOwner() != null && row.leaseUntil() != null
                    && row.leaseUntil().isAfter(Instant.now());
        }

        private AgentExecutionRecord row(AgentState state, long sequence, String owner) {
            return new AgentExecutionRecord(state.executionId(), state.userId(), state.datasetId(), state.conversationId(),
                    state.mode(), state.goal(), state.status().name(), state.currentStep(), state.maxSteps(),
                    state.deadlineAt(), state.stateVersion(), sequence, owner,
                    owner == null ? null : Instant.now().plusSeconds(30), null, null, null);
        }

        private AgentExecutionRecord withLease(AgentExecutionRecord row, String owner, Instant until) {
            return new AgentExecutionRecord(row.executionId(), row.userId(), row.datasetId(), row.conversationId(),
                    row.mode(), row.goal(), row.status(), row.currentStep(), row.maxSteps(), row.deadlineAt(),
                    row.stateVersion(), row.lastCheckpointSeq(), owner, until, row.errorCode(), row.errorMessage(),
                    row.finishedAt());
        }

        private AgentExecutionRecord withStatus(AgentExecutionRecord row, String status) {
            return new AgentExecutionRecord(row.executionId(), row.userId(), row.datasetId(), row.conversationId(),
                    row.mode(), row.goal(), status, row.currentStep(), row.maxSteps(), row.deadlineAt(),
                    row.stateVersion(), row.lastCheckpointSeq(), row.leaseOwner(), row.leaseUntil(), row.errorCode(),
                    row.errorMessage(), row.finishedAt());
        }

        private String errorCode(AgentState state) {
            return switch (state.status()) {
                case RECONCILIATION_REQUIRED -> "NON_IDEMPOTENT_ACTION_UNCERTAIN";
                case TIMEOUT -> "DEADLINE_EXCEEDED";
                case CANCELLED -> "CANCELLED";
                case ERROR -> "AGENT_RUNTIME_ERROR";
                default -> null;
            };
        }

        private String errorMessage(AgentState state) {
            return state.result() == null ? null : state.result().answer();
        }

        private boolean terminal(String status) {
            return Set.of("DONE", "ERROR", "TIMEOUT", "CANCELLED", "RECONCILIATION_REQUIRED").contains(status);
        }
    }
}
