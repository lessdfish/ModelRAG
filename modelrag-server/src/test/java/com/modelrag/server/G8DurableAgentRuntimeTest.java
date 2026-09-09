package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.retrieval.RetrievalActionName;
import com.modelrag.agent.runtime.AgentBudgetState;
import com.modelrag.agent.runtime.AgentCheckpointConflictException;
import com.modelrag.agent.runtime.AgentCheckpointService;
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
import com.modelrag.agent.runtime.repository.AgentCheckpointRecord;
import com.modelrag.agent.runtime.repository.AgentCheckpointRepository;
import com.modelrag.agent.runtime.repository.AgentExecutionRecord;
import com.modelrag.agent.runtime.repository.AgentExecutionRepository;
import com.modelrag.common.operation.OperationGuard;
import com.modelrag.qa.dto.QaRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class G8DurableAgentRuntimeTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void stateCodecRoundTripIsVersionedBoundedAndSecretFree() {
        AgentPendingAction pending = new AgentPendingAction("exec:action:1", AgentPendingActionKind.BUSINESS_TOOL,
                "order_lookup", Map.of("query", "order", "Authorization", "secret"),
                "exec:action:1", false, true, Instant.now());
        AgentState state = AgentState.initial("exec", "TOOL_AGENT", "user", 7, null, "find order", 4,
                Instant.now().plusSeconds(30), new AgentBudgetState(3, 0, 0, 2)).toBuilder()
                .observedSources(List.of(new com.modelrag.agent.runtime.AgentObservedSource(
                        11, 12, 13, 14L, "order.docx", "Orders", "PARAGRAPH", .8)))
                .pendingAction(pending).approvalId("approval-1")
                .toolState(Map.of("safe", "value", "authHeaderValue", "secret", "reasoning", "hidden"))
                .build();
        AgentStateCodec codec = new AgentStateCodec(json, 100_000);

        String encoded = codec.encode(state);
        AgentState decoded = codec.decode(encoded);

        assertEquals(state.executionId(), decoded.executionId());
        assertEquals(state.mode(), decoded.mode());
        assertEquals(state.budgets(), decoded.budgets());
        assertEquals(11, decoded.observedSources().get(0).nodeId());
        assertEquals("approval-1", decoded.approvalId());
        assertEquals(0, decoded.checkpointSeq());
        assertFalse(encoded.contains("secret"));
        assertFalse(encoded.contains("reasoning"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(encoded.replace("\"stateVersion\":1", "\"stateVersion\":2")));
    }

    @Test
    void codecRejectsOversizedCheckpoint() {
        AgentState state = AgentState.initial("exec", "AGENTIC_RAG", "user", 7, null,
                "x".repeat(500), 4, Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new AgentStateCodec(json, 128).encode(state));
    }

    @Test
    void checkpointUsesSequenceCasAndLeaseWinner() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService service = new AgentCheckpointService(store, store, new AgentStateCodec(json, 100_000), 30);
        AgentState initial = AgentState.initial("exec", "AGENTIC_RAG", "user", 7, null, "question", 4,
                Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2));

        AgentState first = service.createAndCheckpoint(initial, "owner-a");
        AgentState second = service.checkpoint(first.toBuilder().currentStep(1).build(), "owner-a");

        assertEquals(1, first.checkpointSeq());
        assertEquals(2, second.checkpointSeq());
        assertThrows(AgentCheckpointConflictException.class,
                () -> service.checkpoint(first.toBuilder().currentStep(1).build(), "owner-a"));
        assertFalse(store.tryClaim("exec", "owner-b", Duration.ofSeconds(30)));
        assertTrue(store.release("exec", "owner-a"));
        assertTrue(store.tryClaim("exec", "owner-b", Duration.ofSeconds(30)));
    }

    @Test
    void runtimeWritesPendingBeforeExecutingAndFinishesOnce() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService service = new AgentCheckpointService(store, store, new AgentStateCodec(json, 100_000), 30);
        ScriptedHandler handler = new ScriptedHandler(store);
        AgentRuntime runtime = runtime(service, store, handler, mock(ApprovalGate.class));

        var result = runtime.start(new AgentStartCommand(
                new QaRequest(7, "question", null, "user", Set.of()), "exec", "AGENTIC_RAG", ""));

        assertEquals("DONE", result.status());
        assertEquals("finished", result.answer());
        assertEquals(1, handler.executions);
        assertTrue(store.pendingWasWrittenBeforeExecute);
        assertTrue(store.writes.stream().anyMatch(state -> state.checkpointSeq() == 2
                && state.pendingAction() != null));
    }

    @Test
    void recoveryReplaysReadOnlyPendingActionButNotNonIdempotentSideEffect() {
        Store readStore = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService readService = new AgentCheckpointService(readStore, readStore,
                new AgentStateCodec(json, 100_000), 30);
        ScriptedHandler readHandler = new ScriptedHandler(readStore);
        AgentRuntime readRuntime = runtime(readService, readStore, readHandler, mock(ApprovalGate.class));
        AgentState pending = AgentState.initial("read", "AGENTIC_RAG", "user", 7, null, "question", 4,
                Instant.now().plusSeconds(30), AgentBudgetState.of(4, 2, 2)).toBuilder()
                .pendingAction(new AgentPendingAction("read:action:1", AgentPendingActionKind.RETRIEVAL,
                        RetrievalActionName.SEARCH_KNOWLEDGE.name(), Map.of("query", "question"),
                        "read:action:1", false, true, Instant.now())).build();
        readStore.create(pending);
        readStore.tryClaim("read", readRuntime.ownerId(), Duration.ofSeconds(30));
        AgentState stored = readService.checkpoint(pending, readRuntime.ownerId());
        var replayed = readRuntime.resume("read", ResumeReason.PROCESS_RECOVERY);

        assertEquals("DONE", replayed.status());
        assertEquals(1, readHandler.executions);
        assertEquals(1, stored.checkpointSeq());

        Store sideStore = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService sideService = new AgentCheckpointService(sideStore, sideStore,
                new AgentStateCodec(json, 100_000), 30);
        ScriptedHandler sideHandler = new ScriptedHandler(sideStore);
        AgentRuntime sideRuntime = runtime(sideService, sideStore, sideHandler, mock(ApprovalGate.class));
        // Build the same state with a non-idempotent business action without sharing mutable runtime state.
        AgentState sidePending = AgentState.initial("side", "AGENTIC_RAG", "user", 7, null, "change", 4,
                Instant.now().plusSeconds(30), new AgentBudgetState(4, 0, 0, 4)).toBuilder()
                .pendingAction(new AgentPendingAction("side:action:1", AgentPendingActionKind.BUSINESS_TOOL,
                        "change_order", Map.of("query", "change"), "side:action:1", false, false, Instant.now()))
                .build();
        sideStore.create(sidePending);
        sideStore.tryClaim("side", sideRuntime.ownerId(), Duration.ofSeconds(30));
        sideService.checkpoint(sidePending, sideRuntime.ownerId());

        var blocked = sideRuntime.resume("side", ResumeReason.PROCESS_RECOVERY);
        assertEquals("RECONCILIATION_REQUIRED", blocked.status());
        assertEquals(0, sideHandler.executions);
    }

    @Test
    void approvalResumeLoadsPersistedPendingAction() {
        Store store = new Store(new AgentStateCodec(json, 100_000));
        AgentCheckpointService service = new AgentCheckpointService(store, store, new AgentStateCodec(json, 100_000), 30);
        ScriptedHandler handler = new ScriptedHandler(store);
        ApprovalGate approvals = mock(ApprovalGate.class);
        AgentRuntime runtime = runtime(service, store, handler, approvals);
        AgentState waiting = AgentState.initial("approval-exec", "AGENTIC_RAG", "user", 7, null, "change", 4,
                Instant.now().plusSeconds(30), new AgentBudgetState(4, 0, 0, 4)).toBuilder()
                .status(AgentRuntimeStatus.WAITING_APPROVAL)
                .pendingAction(new AgentPendingAction("approval-exec:action:1", AgentPendingActionKind.BUSINESS_TOOL,
                        "change_order", Map.of("query", "change"), "approval-exec:action:1", true, false, Instant.now()))
                .approvalId("approval-1").build();
        store.create(waiting.toBuilder().status(AgentRuntimeStatus.RUNNING).build());
        store.tryClaim("approval-exec", runtime.ownerId(), Duration.ofSeconds(30));
        service.checkpoint(waiting, runtime.ownerId());
        when(approvals.get("approval-1")).thenReturn(Optional.of(new ApprovalRecord("approval-1", "approval-exec",
                "change_order", "{\"query\":\"change\"}", "APPROVED", "approver",
                Instant.now().plusSeconds(60), "user", 7L, null)));

        var result = runtime.resumeAfterApproval("approval-1");

        assertEquals("DONE", result.status());
        assertEquals(1, handler.executions);
        assertEquals("approval-exec:action:1", handler.lastActionId);
    }

    private AgentRuntime runtime(AgentCheckpointService service, Store store, AgentModeHandler handler,
            ApprovalGate approvals) {
        return new AgentRuntime(service, store, List.of(handler), approvals, (OperationGuard) () -> { }, json, 4, 5_000);
    }

    private static final class ScriptedHandler implements AgentModeHandler {
        private final Store store;
        private int executions;
        private String lastActionId;

        private ScriptedHandler(Store store) { this.store = store; }

        @Override public String mode() { return "AGENTIC_RAG"; }

        @Override
        public AgentModeDecision decide(AgentState state) {
            if (state.currentStep() == 0 && state.pendingAction() == null) {
                return AgentModeDecision.action(new AgentPendingAction(state.executionId() + ":action:1",
                        AgentPendingActionKind.RETRIEVAL, RetrievalActionName.SEARCH_KNOWLEDGE.name(),
                        Map.of("query", state.goal()), state.executionId() + ":action:1", false, true, Instant.now()));
            }
            return AgentModeDecision.terminal(new AgentResultSnapshot("DONE", "finished", List.of(), 1, false,
                    state.executionId(), List.of()));
        }

        @Override
        public AgentState execute(AgentState state, AgentPendingAction action) {
            executions++;
            lastActionId = action.actionId();
            AgentState latest = store.latestState();
            if (latest == null || latest.pendingAction() == null
                    || !latest.pendingAction().actionId().equals(action.actionId())) {
                throw new AssertionError("pending action was not checkpointed before execution");
            }
            return state.toBuilder().currentStep(state.currentStep() + 1)
                    .budgets(state.budgets().consumeStep()).pendingAction(null).build();
        }
    }

    private static final class Store implements AgentExecutionRepository, AgentCheckpointRepository {
        private final AgentStateCodec codec;
        private final Map<String, AgentExecutionRecord> rows = new ConcurrentHashMap<>();
        private final Map<String, AgentCheckpointRecord> latest = new ConcurrentHashMap<>();
        private final List<AgentState> writes = new ArrayList<>();
        private boolean pendingWasWrittenBeforeExecute;

        private Store(AgentStateCodec codec) { this.codec = codec; }

        @Override public synchronized void create(AgentState state) {
            rows.putIfAbsent(state.executionId(), row(state, 0, null));
        }

        @Override public synchronized Optional<AgentExecutionRecord> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override public synchronized boolean tryClaim(String id, String owner, Duration lease) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !"RUNNING".equals(row.status())
                    || (row.leaseOwner() != null && !row.leaseOwner().equals(owner))) return false;
            rows.put(id, withLease(row, owner, Instant.now().plus(lease))); return true;
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
            rows.put(id, withLease(row, owner, Instant.now().plus(lease))); return true;
        }

        @Override public synchronized boolean release(String id, String owner) {
            AgentExecutionRecord row = rows.get(id);
            if (row == null || !owner.equals(row.leaseOwner())) return false;
            rows.put(id, withLease(row, null, null)); return true;
        }

        @Override public synchronized List<String> findRecoverable(int limit) { return List.of(); }

        @Override public synchronized boolean save(AgentState state, String stateJson, String owner) {
            AgentExecutionRecord row = rows.get(state.executionId());
            if (row == null || row.lastCheckpointSeq() != state.checkpointSeq()
                    || !owner.equals(row.leaseOwner())) return false;
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
                    leaseOwner == null ? null : Instant.now().plusSeconds(30), null, null,
                    terminal(decoded.status().name()) ? Instant.now() : null));
            if (decoded.pendingAction() != null) pendingWasWrittenBeforeExecute = true;
            return true;
        }

        @Override public synchronized Optional<AgentCheckpointRecord> latest(String executionId) {
            return Optional.ofNullable(latest.get(executionId));
        }

        private AgentState latestState() {
            if (latest.isEmpty()) return null;
            return codec.decode(latest.values().stream().findFirst().orElseThrow().stateJson());
        }

        private AgentExecutionRecord row(AgentState state, long seq, String owner) {
            return new AgentExecutionRecord(state.executionId(), state.userId(), state.datasetId(), state.conversationId(),
                    state.mode(), state.goal(), state.status().name(), state.currentStep(), state.maxSteps(),
                    state.deadlineAt(), state.stateVersion(), seq, owner, owner == null ? null : Instant.now().plusSeconds(30),
                    null, null, null);
        }

        private AgentExecutionRecord withLease(AgentExecutionRecord row, String owner, Instant until) {
            return new AgentExecutionRecord(row.executionId(), row.userId(), row.datasetId(), row.conversationId(),
                    row.mode(), row.goal(), row.status(), row.currentStep(), row.maxSteps(), row.deadlineAt(),
                    row.stateVersion(), row.lastCheckpointSeq(), owner, until, row.errorCode(), row.errorMessage(),
                    row.finishedAt());
        }

        private boolean terminal(String status) {
            return Set.of("DONE", "ERROR", "TIMEOUT", "CANCELLED", "RECONCILIATION_REQUIRED").contains(status);
        }
    }
}
