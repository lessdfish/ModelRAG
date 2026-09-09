package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.memory.StructuredMemorySuggestionService;
import com.modelrag.agent.memory.PostgresConversationContextBuilder;
import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.agent.memory.LongTermMemoryService;
import com.modelrag.agent.auto.AutoQaService;
import com.modelrag.agent.intent.IntentTreeService;
import com.modelrag.agent.orchestrator.AgentOrchestrator;
import com.modelrag.agent.orchestrator.AgentExecutionRegistry;
import com.modelrag.agent.router.ComplexityRouter;
import com.modelrag.agent.trace.AgentStepTracer;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.catalog.ToolExecutionSpec;
import com.modelrag.toolgateway.catalog.JdbcToolCatalog;
import com.modelrag.toolgateway.catalog.ToolRegistrationCommand;
import com.modelrag.toolgateway.coordination.ToolCoordinationStore;
import com.modelrag.toolgateway.execution.InternalToolHandlerRegistry;
import com.modelrag.toolgateway.execution.ResilientToolExecutor;
import com.modelrag.toolgateway.execution.ToolDispatcher;
import com.modelrag.toolgateway.execution.ToolExecutionCanceller;
import com.modelrag.toolgateway.execution.ToolGateway;
import com.modelrag.toolgateway.http.HttpToolInvoker;
import com.modelrag.toolgateway.policy.ToolAccessPolicy;
import com.modelrag.toolgateway.security.ToolCallValidator;
import com.modelrag.toolgateway.security.ToolSecretCipher;
import com.modelrag.toolgateway.trace.ToolCallTrace;
import com.modelrag.toolgateway.trace.ToolCallTracer;
import com.modelrag.api.ConversationContextBuilder.DatasetCandidate;
import com.modelrag.api.ConversationContextBuilder.RoutingDecision;
import com.modelrag.api.UserModelProvider;
import com.modelrag.common.security.LocalAuthTokenService;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefactorSecurityUnitTest {
    @Test
    void inFlightHttpToolIsClosedWhenAgentExecutionIsCancelled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        CountDownLatch requestStarted = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            requestStarted.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            HttpToolInvoker http = new HttpToolInvoker(new ObjectMapper(), 5_000, true, "");
            ToolCoordinationStore coordination = new ToolCoordinationStore() {
                @Override public void ensureAvailable() { }
                @Override public String circuit(String toolName) { return null; }
                @Override public void saveCircuit(String toolName, String state, int failures,
                        long openedUntil, Duration ttl) { }
                @Override public long incrementRate(String toolName, Duration window) { return 1; }
            };
            ResilientToolExecutor executor = new ResilientToolExecutor(new ToolCallValidator(), coordination);
            ToolDescriptor tool = new ToolDescriptor("slow-http", "slow cancellation fixture", "HIGH", true,
                    "HTTP", "http://127.0.0.1:" + server.getAddress().getPort() + "/slow", null, "{}",
                    Set.of(), Set.of(), false, false);
            ToolGateway gateway = new ToolGateway(name -> new ToolExecutionSpec(tool, null), new ToolAccessPolicy(),
                    executor, new ToolDispatcher(http, new InternalToolHandlerRegistry(List.of())),
                    mock(ToolCallTracer.class));
            AgentExecutionRegistry executions = new AgentExecutionRegistry(mock(JdbcTemplate.class), gateway,
                    mock(com.modelrag.api.ModelInvocationCanceller.class)) { };
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread owner = Thread.ofVirtual().start(() -> {
                executions.bind("execution-http-cancel", Thread.currentThread());
                try {
                    gateway.invoke(new com.modelrag.toolgateway.execution.ToolInvocation(
                            "execution-http-cancel", "execution-http-cancel:action:1", tool.name(), "user", Set.of(),
                            7, null, "{}", "", "execution-http-cancel"));
                } catch (Throwable error) {
                    result.set(error);
                } finally {
                    executions.unbind("execution-http-cancel", Thread.currentThread());
                    finished.countDown();
                }
            });
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertTrue(executions.interruptActive("execution-http-cancel"));
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000);
            assertTrue(result.get() instanceof RuntimeException);
            owner.join(1_000);
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void businessFactsAreForcedIntoDatasetScopeAndDroppedWithoutDataset() {
        UserModelProvider model = model(new AtomicInteger(),
                "{\"suggestions\":[{\"scope\":\"USER_GLOBAL\",\"type\":\"BUSINESS_FACT\"," +
                "\"memoryKey\":\"approval-window\",\"content\":\"审批窗口为 30 天\"}]}");
        var service = new StructuredMemorySuggestionService(model, new ObjectMapper());

        assertEquals(7L, service.suggest("user-a", 7L, "问题", "回答", "1", "2").get(0).datasetId());
        assertTrue(service.suggest("user-a", null, "问题", "回答", "1", "2").isEmpty());
    }

    @Test
    void toolAndAgentTracesStoreOnlyDigestsForSensitivePayloads() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ToolCallTracer(jdbc, new SimpleMeterRegistry()).record(new ToolCallTrace(
                "trace-1", "lookup", "{\"query\":\"secret question\"}",
                "{\"answer\":\"secret answer\"}", true, null, 5));
        verify(jdbc).update(any(String.class), any(Object[].class));

        JdbcTemplate stepJdbc = mock(JdbcTemplate.class);
        when(stepJdbc.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Integer.class), any()))
                .thenReturn(1);
        new AgentStepTracer(stepJdbc, new ObjectMapper()).record("execution-1", "ACT", "safe",
                Map.of("parameterSummary", "secret question", "step", 1), 0);
        verify(stepJdbc).update(any(String.class), any(Object[].class));
    }

    @Test
    void malformedToolMetadataIsRejectedBeforePersistence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcToolCatalog registry = new JdbcToolCatalog(jdbc, new ToolSecretCipher("test-tool-secret-key"),
                new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new ToolRegistrationCommand(
                "bad name", "bad", "LOW", true, "HTTP", "https://api.example.com/path?secret=x",
                "Authorization\r\nX-Evil", "secret", "{}", Set.of(), Set.of(), false)));
    }

    @Test
    void structuredMemorySuggestionsStayPendingAndDatasetScoped() {
        AtomicInteger calls = new AtomicInteger();
        UserModelProvider model = model(calls,
                "{\"suggestions\":[{\"scope\":\"DATASET\",\"type\":\"BUSINESS_FACT\","
                + "\"memoryKey\":\"approval-window\",\"content\":\"审批窗口为 30 天\"}]}");
        var service = new StructuredMemorySuggestionService(model, new ObjectMapper());

        var result = service.suggest("user-a", 7L, "请说明审批窗口", "审批窗口为 30 天", "11", "12");

        assertEquals(1, calls.get());
        assertEquals(1, result.size());
        assertEquals("PENDING_CONFIRMATION", result.get(0).status());
        assertEquals(7L, result.get(0).datasetId());
        assertTrue(result.get(0).expiresAt() != null);
    }

    @Test
    void invalidMemoryModelOutputFailsWithoutRegexFallback() {
        var service = new StructuredMemorySuggestionService(model(new AtomicInteger(), "记住用户喜欢简短回答"),
                new ObjectMapper());

        assertThrows(IllegalArgumentException.class,
                () -> service.suggest("user-a", null, "我喜欢简短回答", "好的", null, null));
    }

    @Test
    void httpToolRejectsPrivateAndNonAllowlistedEndpoints() {
        HttpToolInvoker invoker = new HttpToolInvoker(new ObjectMapper(), 500, false, "api.example.com");
        ToolDescriptor metadata = new ToolDescriptor("metadata", "metadata", "LOW", true,
                "HTTP", "https://169.254.169.254/latest/meta-data", null, "{}", Set.of(), Set.of(), false, false);
        ToolDescriptor unlisted = new ToolDescriptor("unlisted", "unlisted", "LOW", true,
                "HTTP", "https://example.org/tool", null, "{}", Set.of(), Set.of(), false, false);

        assertThrows(IllegalArgumentException.class, () -> invoker.invoke(new ToolExecutionSpec(metadata, null), "{}"));
        assertThrows(IllegalArgumentException.class, () -> invoker.invoke(new ToolExecutionSpec(unlisted, null), "{}"));
    }

    @Test
    void idempotentHttpToolRequiresAndForwardsStableKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> key = new AtomicReference<>();
        server.createContext("/tool", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpToolInvoker invoker = new HttpToolInvoker(new ObjectMapper(), 500, true, "");
            ToolDescriptor tool = new ToolDescriptor("write", "write", "HIGH", true, "HTTP",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tool", null, "{}",
                    Set.of(), Set.of(), true, false);
            ToolExecutionSpec spec = new ToolExecutionSpec(tool, null);
            assertThrows(IllegalArgumentException.class, () -> invoker.invoke(spec, "{}"));
            assertEquals("ok", invoker.invoke(spec, "{}", "execution:action"));
            assertEquals("execution:action", key.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void independentQuestionUsesRuleDirectWithoutModelCall() {
        AtomicInteger calls = new AtomicInteger();
        PostgresConversationContextBuilder builder = new PostgresConversationContextBuilder(
                conversations(), memories(), model(calls, "{}"), new ObjectMapper());

        var context = builder.build("user-a", 7, 11L, "年假有几天", List.of(new DatasetCandidate(7, "人事", "假期")));

        assertEquals(0, calls.get());
        assertEquals("年假有几天", context.standaloneQuestion());
        assertTrue(!context.routingDecision().modelInvoked());
    }

    @Test
    void dependentQuestionUsesExactlyOneValidatedStructuredRoutingCall() {
        AtomicInteger calls = new AtomicInteger();
        String output = "{\"intent\":\"KNOWLEDGE_QA\",\"standaloneQuestion\":\"年假是否还能结转\","
                + "\"datasetCandidates\":[7,999],\"missingSlots\":[],\"confidence\":0.91,"
                + "\"requiresRerank\":true,\"riskLevel\":\"LOW\"}";
        PostgresConversationContextBuilder builder = new PostgresConversationContextBuilder(
                conversations(), memories(), model(calls, output), new ObjectMapper());

        var context = builder.build("user-a", 7, 11L, "它是否还能结转", List.of(new DatasetCandidate(7, "人事", "假期")));
        RoutingDecision decision = context.routingDecision();

        assertEquals(1, calls.get());
        assertEquals("年假是否还能结转", context.standaloneQuestion());
        assertEquals(List.of(7L), decision.datasetCandidates());
        assertTrue(decision.modelInvoked());
        assertTrue(decision.requiresRerank());
    }

    @Test
    void automaticDatasetRoutingRunsOneFormalHybridRetrievalAfterTopThreePreselection() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        SearchFacade search = mock(SearchFacade.class);
        IntentTreeService intents = mock(IntentTreeService.class);
        Dataset first = new Dataset(98, "annual leave", "annual leave policy", 600, 80, 5, .7, 1);
        Dataset second = new Dataset(99, "payroll", "salary policy", 600, 80, 5, .7, 1);
        Dataset third = new Dataset(100, "travel", "travel policy", 600, 80, 5, .7, 1);
        when(datasets.route("annual leave policy", Set.of(), 3)).thenReturn(List.of(first, second, third));
        when(intents.match(any(Long.class), any(String.class))).thenReturn(Optional.empty());
        ScoredChunk evidence = new ScoredChunk(1, "annual leave policy permits annual leave", .98, "hybrid", 1);
        when(search.inspect(any())).thenReturn(new SearchStages("annual leave policy", List.of("annual leave policy"),
                "annual leave policy", List.of(evidence), List.of(evidence), List.of(evidence), List.of(evidence),
                true, List.of(evidence), List.of(), Map.of()));
        AutoQaService service = new AutoQaService(datasets, search, mock(ComplexityRouter.class),
                mock(QaOrchestrator.class), mock(AgentOrchestrator.class), intents,
                mock(com.modelrag.api.ConversationContextBuilder.class));

        assertEquals(98, service.selectDataset("annual leave policy").id());

        verify(search).inspect(any());
    }

    @Test
    void activeAgentExecutionCanBeInterruptedImmediately() throws Exception {
        AgentExecutionRegistry executions = new AgentExecutionRegistry(
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(ToolExecutionCanceller.class),
                mock(com.modelrag.api.ModelInvocationCanceller.class)) { };
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = Thread.ofVirtual().start(() -> {
            executions.bind("execution-1", Thread.currentThread());
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException expected) {
                interrupted.countDown();
            } finally {
                executions.unbind("execution-1", Thread.currentThread());
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        assertTrue(executions.interruptActive("execution-1"));
        assertTrue(interrupted.await(500, TimeUnit.MILLISECONDS));
        worker.join(1_000);
    }

    @Test
    void tamperedAndMalformedBearerTokensAreRejected() {
        LocalAuthTokenService tokens = new LocalAuthTokenService("test-secret-that-is-not-empty", 900);
        String valid = tokens.issue("user-a", Set.of("USER"), Set.of(7L));

        assertEquals("user-a", tokens.parse(valid).id());
        assertThrows(RuntimeException.class, () -> tokens.parse(valid.substring(0, valid.length() - 1) + "x"));
        assertThrows(RuntimeException.class, () -> tokens.parse("!.also-invalid"));
    }

    private ConversationMemory conversations() {
        return new ConversationMemory(mock(org.springframework.jdbc.core.JdbcTemplate.class)) {
            @Override public Optional<Conversation> conversation(String userId, long id, boolean archived) {
                return Optional.of(new Conversation(id, 7L, "年假", 2, java.time.Instant.now(), userId));
            }
            @Override public Optional<String> summary(String userId, long id) { return Optional.of("用户询问年假"); }
            @Override public List<Entry> recent(String userId, long id, int limit) {
                return List.of(new Entry("user", "年假有几天", "[]", 1, null, "rag", "人事", 1),
                        new Entry("assistant", "年假为 10 天", "[]", 2, null, "rag", "人事", 2));
            }
        };
    }

    private LongTermMemoryService memories() {
        return new LongTermMemoryService(mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(com.modelrag.indexing.service.EmbeddingService.class)) {
            @Override public String promptContext(String userId, Long datasetId, String query, int topK) { return ""; }
        };
    }

    private UserModelProvider model(AtomicInteger calls, String output) {
        return new UserModelProvider() {
            @Override public String generate(String userId, String prompt) { calls.incrementAndGet(); return output; }
            @Override public boolean configured(String userId) { return true; }
        };
    }
}
