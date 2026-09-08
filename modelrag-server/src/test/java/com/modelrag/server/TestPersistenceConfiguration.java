package com.modelrag.server;

import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.agent.memory.LongTermMemoryService;
import com.modelrag.agent.approval.ApprovalGate;
import com.modelrag.agent.approval.ApprovalRecord;
import com.modelrag.agent.orchestrator.AgentExecutionRegistry;
import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.agent.tool.ToolRegistry;
import com.modelrag.agent.tool.ResilientToolExecutor;
import com.modelrag.agent.tool.ToolCallValidator;
import com.modelrag.agent.tool.ToolCoordinationStore;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.rate.DatasetRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import com.modelrag.common.security.RequestUser;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.LocalAuthTokenService;
import com.modelrag.common.operation.OperationGuard;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.sse.SseReplayStore;
import com.modelrag.qa.feedback.QaFeedbackService;
import com.modelrag.qa.feedback.FeedbackView;
import com.modelrag.qa.trace.QaTraceStore;
import com.modelrag.server.eval.EvalController;
import com.modelrag.search.facade.SearchFacade;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import com.modelrag.knowledge.repository.IndexVersionRepository;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.indexing.store.RetrievalEmbeddingRepository;
import com.modelrag.knowledge.service.InMemoryKnowledgeStore;
import com.modelrag.knowledge.service.DocumentLifecycleService;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.api.ConversationContextBuilder;
import com.modelrag.api.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import jakarta.servlet.http.HttpServletRequest;

/** Explicit domain fakes for unit-style Spring tests; production profiles have no such beans. */
@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestPersistenceConfiguration {
    @Bean
    DatasetRepository datasetRepository(InMemoryKnowledgeStore store) { return new TestDatasetRepository(store); }

    @Bean
    DocumentRepository documentRepository(InMemoryKnowledgeStore store) { return new TestDocumentRepository(store); }

    @Bean
    DocumentVersionRepository documentVersionRepository() { return new TestDocumentVersionRepository(); }

    @Bean
    DocumentStructureRepository documentStructureRepository() { return new TestDocumentStructureRepository(); }

    @Bean
    IndexBuildRepository indexBuildRepository() { return new TestIndexBuildRepository(); }

    @Bean
    TestRetrievalUnitRepository retrievalUnitRepository() { return new TestRetrievalUnitRepository(); }

    @Bean
    RetrievalEmbeddingRepository retrievalEmbeddingRepository(TestRetrievalUnitRepository units) {
        return new TestRetrievalEmbeddingRepository(units);
    }

    @Bean
    DocumentLifecycleService documentLifecycleService(DatasetRepository datasets, DocumentRepository documents,
            DocumentVersionRepository versions, TransactionOperations transactions) {
        return new DocumentLifecycleService(datasets, documents, versions, transactions);
    }

    @Bean
    ChunkRepository chunkRepository(InMemoryKnowledgeStore store) { return new TestChunkRepository(store); }

    @Bean
    IndexVersionRepository indexVersionRepository(InMemoryKnowledgeStore store) { return new TestIndexVersionRepository(store); }

    @Bean
    TransactionOperations testTransactionOperations() {
        return new TransactionOperations() {
            @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    @Bean
    AccessControlService accessControlService(HttpServletRequest request, LocalAuthTokenService tokens) {
        return new AccessControlService(request, tokens, null) {
            @Override public RequestUser currentUser() {
                String header = request.getHeader("Authorization");
                return header == null || header.isBlank()
                        ? new RequestUser("dev-admin", java.util.Set.of("ADMIN", "APPROVER"), java.util.Set.of())
                        : tokens.parse(header.regionMatches(true, 0, "Bearer ", 0, 7)
                                ? header.substring(7).trim() : header.trim());
            }
        };
    }

    @Bean
    ConversationMemory conversationMemory() { return new TestConversationMemory(); }

    @Bean
    ConversationRepository conversationRepository(ConversationMemory memory) {
        return new ConversationRepository() {
            @Override public long create(String userId, Long datasetId, String title) {
                return memory.create(userId, datasetId, title);
            }
            @Override public List<Conversation> list(String userId, boolean includeArchived) {
                return memory.conversations(userId, includeArchived).stream()
                        .map(item -> new Conversation(item.id(), item.datasetId(), item.title(),
                                item.messageCount(), item.updateTime())).toList();
            }
            @Override public List<ConversationContextBuilder.Message> recentMessages(
                    String userId, long conversationId, int limit) {
                return memory.recent(userId, conversationId, limit).stream()
                        .map(item -> new ConversationContextBuilder.Message(
                                item.role(), item.content(), item.messageId())).toList();
            }
            @Override public void append(String userId, long conversationId, String role, String content) {
                memory.append(userId, conversationId, role, content, "[]", null, "rag", null);
            }
        };
    }

    @Bean
    ConversationContextBuilder conversationContextBuilder(ConversationMemory memory) {
        return (userId, datasetId, conversationId, question) -> new ConversationContextBuilder.ConversationContext(
                conversationId == null ? "" : memory.summary(userId, conversationId).orElse(""),
                conversationId == null ? List.of() : memory.recent(userId, conversationId, 8).stream()
                        .map(item -> new ConversationContextBuilder.Message(
                                item.role(), item.content(), item.messageId())).toList(),
                "", question == null ? "" : question);
    }

    @Bean
    DatasetRateLimiter datasetRateLimiter() {
        return new DatasetRateLimiter(org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class), true) {
            @Override public void check(long datasetId) { }
            @Override public void checkUpload(String userId) { }
            @Override public void checkModel(String userId) { }
        };
    }

    @Bean
    OperationGuard operationGuard() { return () -> { }; }

    @Bean
    SseReplayStore sseReplayStore() {
        Map<String, List<SseEvent>> values = new ConcurrentHashMap<>();
        return new SseReplayStore() {
            @Override public void append(String key, SseEvent event) {
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
            }
            @Override public List<SseEvent> replay(String key) {
                return List.copyOf(values.getOrDefault(key, List.of()));
            }
        };
    }

    @Bean
    LongTermMemoryService longTermMemoryService() { return new TestLongTermMemoryService(); }

    @Bean
    ApprovalGate approvalGate() { return new TestApprovalGate(); }

    @Bean
    AgentExecutionRegistry agentExecutionRegistry() { return new TestAgentExecutionRegistry(); }

    @Bean
    ToolRegistry toolRegistry() { return new TestToolRegistry(); }

    @Bean
    ToolCoordinationStore toolCoordinationStore() {
        Map<String, String> circuits = new ConcurrentHashMap<>();
        Map<String, RateWindow> rates = new ConcurrentHashMap<>();
        return new ToolCoordinationStore() {
            @Override public void ensureAvailable() { }
            @Override public String circuit(String toolName) { return circuits.get(toolName); }
            @Override public void saveCircuit(String toolName, String state, int failures, long openedUntil, Duration ttl) {
                circuits.put(toolName, state + "|" + failures + "|" + openedUntil);
            }
            @Override public long incrementRate(String toolName, Duration window) {
                long now = System.currentTimeMillis();
                RateWindow next = rates.compute(toolName, (key, old) -> old == null || now - old.startedAt() >= window.toMillis()
                        ? new RateWindow(now, 1) : new RateWindow(old.startedAt(), old.count() + 1));
                return next.count();
            }
        };
    }

    @Bean
    ResilientToolExecutor resilientToolExecutor(ToolCallValidator validator, ToolCoordinationStore coordination) {
        return new ResilientToolExecutor(validator, coordination);
    }

    private record RateWindow(long startedAt, long count) { }

    @Bean
    TokenUsageTracker tokenUsageTracker(MeterRegistry metrics) { return new TestTokenUsageTracker(metrics); }

    @Bean
    QaFeedbackService qaFeedbackService() { return new TestQaFeedbackService(); }

    @Bean
    QaTraceStore qaTraceStore() { return new TestQaTraceStore(); }

    @Bean
    EvalController evalController(QaOrchestrator qa, SearchFacade search, DatasetRepository datasets,
            ChunkRepository chunks,
            ObjectMapper json, AccessControlService access, ObjectProvider<com.modelrag.common.model.ModelGateway> models,
            @Value("${modelrag.eval.llm-judge-enabled:false}") boolean llmJudgeEnabled) {
        return new EvalController(qa, search, datasets, chunks,
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                json, access, models, llmJudgeEnabled);
    }

    static final class TestQaTraceStore implements QaTraceStore {
        private final Map<String, Map<String, Object>> traces = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> audits = new ConcurrentHashMap<>();

        @Override
        public void saveTrace(Map<String, Object> trace) {
            traces.put(String.valueOf(trace.get("traceId")), new java.util.LinkedHashMap<>(trace));
        }

        @Override
        public List<Map<String, Object>> traces() { return List.copyOf(traces.values()); }

        @Override
        public Map<String, Object> replay(String traceId) {
            Map<String, Object> trace = traces.get(traceId);
            if (trace == null) return Map.of("traceId", traceId, "found", false);
            Map<String, Object> result = new java.util.LinkedHashMap<>(trace);
            result.put("found", true);
            Map<String, Object> audit = audits.get(traceId);
            result.put("answer", audit == null ? null : audit.get("answer"));
            result.put("citations", audit == null ? "[]" : audit.get("citations"));
            return result;
        }

        @Override
        public void saveAudit(Map<String, Object> audit) {
            audits.put(String.valueOf(audit.get("traceId")), new java.util.LinkedHashMap<>(audit));
        }

        @Override
        public List<Map<String, Object>> audits() { return List.copyOf(audits.values()); }
    }

    static final class TestQaFeedbackService extends QaFeedbackService {
        private final List<FeedbackView> values = new ArrayList<>();

        TestQaFeedbackService() { super(null); }

        @Override
        public FeedbackView submit(long datasetId, String traceId, String userId, String rating,
                String comment) {
            FeedbackView value = new FeedbackView(values.size() + 1L, traceId, datasetId, userId, rating,
                    comment == null ? "" : comment, Instant.now());
            values.add(value);
            return value;
        }

        @Override
        public List<FeedbackView> list() { return List.copyOf(values); }
    }

    static final class TestTokenUsageTracker extends TokenUsageTracker {
        TestTokenUsageTracker(MeterRegistry metrics) {
            super(metrics, org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class));
        }
        @Override public Usage record(long datasetId, String prompt, String completion) { return usage(datasetId); }
        @Override public void recordEmbedding(long datasetId, String text) { }
        @Override public void recordRerank(long datasetId) { }
        @Override public Usage usage(long datasetId) { return new Usage(0, 0, 0, 0, 0, 0, 0, 0, false); }
    }

    static final class TestConversationMemory extends ConversationMemory {
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, Conversation> conversations = new ConcurrentHashMap<>();
        private final Map<Long, List<Entry>> messages = new ConcurrentHashMap<>();

        TestConversationMemory() { super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class)); }

        @Override
        public long create(String userId, Long datasetId, String title) {
            long id = ids.incrementAndGet();
            conversations.put(id, new Conversation(id, datasetId, title, 0, Instant.now(), userId));
            return id;
        }

        @Override
        public List<Conversation> conversations(String userId, boolean includeArchived) {
            return conversations.values().stream().filter(item -> item.userId().equals(userId))
                    .sorted(Comparator.comparing(Conversation::updateTime).reversed()).toList();
        }

        @Override
        public Optional<Conversation> conversation(String userId, long conversationId, boolean includeArchived) {
            Conversation value = conversations.get(conversationId);
            return value != null && value.userId().equals(userId) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public void archive(String userId, long conversationId) {
            if (!conversation(userId, conversationId, true).isPresent()) throw new IllegalArgumentException("会话不存在");
        }

        @Override
        public void delete(String userId, long conversationId) {
            if (!conversation(userId, conversationId, true).isPresent()) throw new IllegalArgumentException("会话不存在");
            conversations.remove(conversationId);
            messages.remove(conversationId);
        }

        @Override
        public void append(String userId, long conversationId, String role, String content, String citations,
                String traceId, String mode, String datasetName) {
            Conversation current = conversation(userId, conversationId, true).orElseThrow();
            messages.computeIfAbsent(conversationId, ignored -> new ArrayList<>())
                    .add(new Entry(role, content, citations, System.currentTimeMillis(), traceId, mode, datasetName));
            conversations.put(conversationId, new Conversation(current.id(), current.datasetId(), current.title(),
                    current.messageCount() + 1, Instant.now(), current.userId()));
        }

        @Override
        public List<Entry> history(String userId, long conversationId, int page, int size) {
            if (!conversation(userId, conversationId, true).isPresent()) return List.of();
            return messages.getOrDefault(conversationId, List.of()).stream().skip((long) Math.max(0, page - 1) * size)
                    .limit(size).toList();
        }

        @Override public boolean belongsTo(String userId, long conversationId) { return conversation(userId, conversationId, true).isPresent(); }
        @Override public String contextualQuery(String userId, long conversationId, String query) { return query; }
        @Override public Optional<String> summary(String userId, long conversationId) { return Optional.empty(); }
    }

    static final class TestLongTermMemoryService extends LongTermMemoryService {
        private final List<Memory> values = new ArrayList<>();
        TestLongTermMemoryService() {
            super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                    org.mockito.Mockito.mock(com.modelrag.indexing.service.EmbeddingService.class));
        }
        @Override public synchronized Memory upsert(Memory memory) { values.add(memory); return memory; }
        @Override public synchronized List<Memory> retrieveRelevant(String userId, String query, int topK) {
            return values.stream().filter(memory -> userId.equals(memory.userId())).limit(topK).toList();
        }
        @Override public String promptContext(String userId, String query, int topK) { return ""; }
        @Override public Memory confirm(String userId, String id) { return find(id); }
        @Override public Memory reject(String userId, String id) { return find(id); }
        @Override public Memory pause(String userId, String id) { return find(id); }
        @Override public Memory resume(String userId, String id) { return find(id); }
        @Override public void delete(String userId, String id) { values.removeIf(memory -> id.equals(memory.id())); }
        @Override public void clear(String userId, Long datasetId) { values.removeIf(memory -> userId.equals(memory.userId())); }
        private Memory find(String id) { return values.stream().filter(memory -> id.equals(memory.id())).findFirst().orElseThrow(); }
    }

    static final class TestApprovalGate extends ApprovalGate {
        private final Map<String, ApprovalRecord> records = new ConcurrentHashMap<>();
        TestApprovalGate() { super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class)); }
        @Override public ApprovalRecord request(String executionId, String tool, String params,
                String requesterUserId, Long datasetId, Long conversationId) {
            String id = UUID.randomUUID().toString();
            ApprovalRecord value = new ApprovalRecord(id, executionId, tool, params, "PENDING", null,
                    Instant.now().plusSeconds(300), requesterUserId, datasetId, conversationId);
            records.put(id, value);
            return value;
        }
        @Override public ApprovalRecord decide(String id, boolean approved, String userId) {
            ApprovalRecord old = records.get(id);
            ApprovalRecord next = old.decided(approved ? "APPROVED" : "REJECTED", userId);
            records.put(id, next); return next;
        }
        @Override public ApprovalRecord validateDecision(String id, String approver, Long datasetId, Long conversationId) {
            return records.get(id);
        }
        @Override public Optional<ApprovalRecord> get(String id) { return Optional.ofNullable(records.get(id)); }
        @Override public List<com.modelrag.agent.approval.ApprovalSnapshot> list() { return List.of(); }
        @Override public List<com.modelrag.agent.approval.ApprovalSnapshot> pendingFor(RequestUser user) { return List.of(); }
    }

    static final class TestAgentExecutionRegistry extends AgentExecutionRegistry {
        private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
        TestAgentExecutionRegistry() { super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                org.mockito.Mockito.mock(ResilientToolExecutor.class),
                org.mockito.Mockito.mock(com.modelrag.agent.tool.HttpToolInvoker.class),
                org.mockito.Mockito.mock(com.modelrag.api.ModelInvocationCanceller.class)); }
        @Override public void register(String id, String userId, long datasetId, Long conversationId) {
            scopes.put(id, new Scope(userId, datasetId, conversationId));
        }
        @Override public void requireSubscribe(RequestUser user, String id) {
            Scope scope = scopes.get(id);
            if (scope == null || (!user.hasRole("ADMIN") && !user.id().equals(scope.requesterUserId()))) {
                throw new IllegalArgumentException("无权订阅 Agent 执行流");
            }
        }
        @Override public void complete(String id, String status) { }
    }

    static final class TestToolRegistry extends ToolRegistry {
        private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
        TestToolRegistry() {
            super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                    new com.modelrag.agent.tool.ToolSecretCipher("test-tool-secret-key-at-least-32-bytes"));
            tools.put("knowledge_lookup", new ToolDefinition("knowledge_lookup", "检索已授权知识库", "LOW", true));
            tools.put("destructive_operation", new ToolDefinition("destructive_operation", "执行删除或变更操作", "HIGH", true));
        }
        @Override public ToolDefinition register(ToolDefinition tool) { tools.put(tool.name(), tool); return tool; }
        @Override public ToolDefinition remove(String name) { return tools.remove(name); }
        @Override public ToolDefinition setEnabled(String name, boolean enabled) {
            ToolDefinition old = get(name);
            return register(new ToolDefinition(old.name(), old.description(), old.riskLevel(), enabled, old.type(),
                    old.endpoint(), old.authHeaderName(), old.authHeaderValue(), old.jsonSchema(), old.allowedRoles(),
                    old.allowedDatasetIds(), old.idempotent()));
        }
        @Override public ToolDefinition get(String name) {
            ToolDefinition tool = tools.get(name);
            if (tool == null || !tool.enabled()) throw new IllegalArgumentException("工具不可用");
            return tool;
        }
        @Override public ToolDefinition getAny(String name) {
            ToolDefinition tool = tools.get(name);
            if (tool == null) throw new IllegalArgumentException("工具不存在");
            return tool;
        }
        @Override public List<ToolDefinition> list() { return List.copyOf(tools.values()); }
        @Override public List<ToolDefinition> listEnabled() { return list().stream().filter(ToolDefinition::enabled).toList(); }
    }
}
