package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.orchestrator.AgenticRetrievalOrchestrator;
import com.modelrag.agent.policy.AgentDecision;
import com.modelrag.agent.policy.AgentDecisionValidator;
import com.modelrag.agent.policy.AgentPolicyInput;
import com.modelrag.agent.policy.LlmAgentPolicy;
import com.modelrag.agent.router.ComplexityRouter;
import com.modelrag.agent.router.RouteDecision;
import com.modelrag.agent.retrieval.DocumentLexicalFindService;
import com.modelrag.agent.retrieval.FollowReferencesAction;
import com.modelrag.agent.retrieval.OpenNodeAction;
import com.modelrag.agent.retrieval.ReadChildrenAction;
import com.modelrag.agent.retrieval.ReadNeighborsAction;
import com.modelrag.agent.retrieval.RetrievalActionExecutor;
import com.modelrag.agent.retrieval.RetrievalActionName;
import com.modelrag.agent.retrieval.RetrievalActionRegistry;
import com.modelrag.agent.retrieval.RetrievalActionRequest;
import com.modelrag.agent.retrieval.RetrievalObservation;
import com.modelrag.agent.retrieval.RetrievalObservationItem;
import com.modelrag.agent.retrieval.RetrievalToolContext;
import com.modelrag.api.UserModelProvider;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.qa.evidence.AnswerSynthesizer;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceLocator;
import com.modelrag.qa.evidence.EvidenceOrigin;
import com.modelrag.qa.evidence.EvidenceRetrievalService;
import com.modelrag.qa.evidence.EvidenceSelector;
import com.modelrag.qa.evidence.EvidenceSufficiency;
import com.modelrag.qa.evidence.EvidenceSufficiencyPolicy;
import com.modelrag.qa.orchestrator.ContextAssembler;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.search.channel.v2.ElasticsearchRetrievalUnitSearch;
import com.modelrag.search.config.V2EmbeddingProfileProvider;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class G7AgenticRetrievalTest {
    @Test
    void classifierAcceptsConfidenceExactlyOne() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/classify", exchange -> {
            byte[] body = "{\"label\":\"DIRECT_RAG\",\"confidence\":1.0}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var beans = new DefaultListableBeanFactory();
            ComplexityRouter router = new ComplexityRouter(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper(),
                    beans.getBeanProvider(com.modelrag.common.model.ModelHealthRegistry.class));
            assertEquals(RouteDecision.DIRECT_RAG, router.route("普通事实问题"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceSpecificActionsRejectUnobservedNodesBeforeNavigation() {
        var navigation = mock(com.modelrag.knowledge.service.DocumentNavigationService.class);
        var context = context(Set.of(55L), Set.of(23L));

        assertThrows(IllegalArgumentException.class, () -> new OpenNodeAction(navigation, 500).execute(
                new RetrievalActionRequest(RetrievalActionName.OPEN_NODE, Map.of("nodeId", 999L)), context));

        verifyNoInteractions(navigation);
    }

    @Test
    void navigationActionsEnforceHardBounds() {
        var navigation = mock(com.modelrag.knowledge.service.DocumentNavigationService.class);
        var context = context(Set.of(55L), Set.of(23L));

        assertThrows(IllegalArgumentException.class, () -> new ReadNeighborsAction(navigation, 500).execute(
                request(RetrievalActionName.READ_NEIGHBORS, "nodeId", 55L, "radius", 3), context));
        assertThrows(IllegalArgumentException.class, () -> new ReadChildrenAction(navigation, 500).execute(
                request(RetrievalActionName.READ_CHILDREN, "nodeId", 55L, "offset", 0, "limit", 21), context));
        assertThrows(IllegalArgumentException.class, () -> new ReadChildrenAction(navigation, 500).execute(
                request(RetrievalActionName.READ_CHILDREN, "nodeId", 55L, "offset", 1_001, "limit", 1), context));
        assertThrows(IllegalArgumentException.class, () -> new FollowReferencesAction(navigation, 500).execute(
                request(RetrievalActionName.FOLLOW_REFERENCES, "nodeId", 55L, "limit", 11), context));

        verifyNoInteractions(navigation);
    }

    @Test
    void referencesMayReturnAnActiveNodeInAnotherDocumentWithinTheDataset() {
        var navigation = mock(com.modelrag.knowledge.service.DocumentNavigationService.class);
        DocumentNode target = node(56, 24, 30, "referenced");
        when(navigation.references(55, 1)).thenReturn(List.of(target));
        var context = context(Set.of(55L), Set.of(23L));

        RetrievalObservation observation = new FollowReferencesAction(navigation, 500).execute(
                request(RetrievalActionName.FOLLOW_REFERENCES, "nodeId", 55L, "limit", 1), context);

        assertEquals(24L, observation.items().get(0).documentId());
        assertEquals(56L, observation.items().get(0).nodeId());
        assertTrue(observation.newEvidence().get(0).retrievalUnitId() == null);
    }

    @Test
    void searchActionReturnsV2ObservationAndEvidence() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        EvidenceRetrievalService evidence = mock(EvidenceRetrievalService.class);
        RetrievalCandidate candidate = new RetrievalCandidate(7, 101, 55, 23, 29, 31,
                com.modelrag.knowledge.model.RetrievalUnitType.PARAGRAPH, "Policy", "content", .9,
                RetrievalChannel.SEMANTIC, 1, Map.of());
        when(retrieval.inspect(any())).thenReturn(new RetrievalV2Stages("question", List.of("question"),
                "question", List.of(candidate), List.of(), List.of(candidate), List.of(), false,
                List.of(candidate)));
        Evidence primary = evidence("primary", 55, true, "content");
        when(evidence.retrieve(7, List.of(candidate))).thenReturn(
                new EvidenceRetrievalService.EvidenceRetrievalResult(List.of(primary), List.of(), 0));

        RetrievalObservation observation = new com.modelrag.agent.retrieval.SearchKnowledgeAction(
                retrieval, evidence, new V2EmbeddingProfileProvider(), 20, 500).execute(
                        request(RetrievalActionName.SEARCH_KNOWLEDGE, "query", "question", "limit", 1),
                        context(Set.of(), Set.of()));

        assertEquals(RetrievalActionName.SEARCH_KNOWLEDGE, observation.action());
        assertEquals(1, observation.items().size());
        assertEquals(1, observation.newEvidence().size());
        assertTrue(observation.newEvidence().get(0).primary());
    }

    @Test
    void documentFindUsesTheV2IndexAndTheDocumentActiveBuild() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/modelrag-retrieval-units-v2/_search", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"hits\":{\"hits\":[{\"_score\":1.1,\"_source\":{"
                    + "\"retrievalUnitId\":101,\"datasetId\":7,\"nodeId\":55,\"documentId\":23,"
                    + "\"documentVersionId\":29,\"indexBuildId\":31,\"unitType\":\"PARAGRAPH\","
                    + "\"titlePath\":\"Policy\",\"content\":\"approval\",\"metadata\":{}}}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            DocumentRepository documents = mock(DocumentRepository.class);
            IndexBuildRepository builds = mock(IndexBuildRepository.class);
            RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
            Document document = new Document(23, 7, "policy.docx", "docx", "hash", "", "READY", null, 1)
                    .withActiveVersionId(29L).withActiveIndexBuildId(31L);
            Instant now = Instant.now();
            IndexBuild build = new IndexBuild(31, 7, 23, 29, 1, IndexBuildState.ACTIVE,
                    "qwen3-v1", "default", 1, 1, 1, 1, null, Map.of(), now, now, now, now, null);
            when(documents.findById(23)).thenReturn(document);
            when(builds.findActiveByDocumentId(23)).thenReturn(Optional.of(build));
            when(units.findActiveByIds(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(
                    List.of(new RetrievalUnit(101, 7, 23, 29, 55, 31, RetrievalUnitType.PARAGRAPH,
                            1, "Policy", "approval", "hash", 1, Map.of(), now)));
            var lexical = new ElasticsearchRetrievalUnitSearch("http://127.0.0.1:" + server.getAddress().getPort(),
                    new ObjectMapper(), units, new SimpleMeterRegistry(), java.net.http.HttpClient.newHttpClient());

            var result = new DocumentLexicalFindService(documents, builds, units, lexical)
                    .find(context(Set.of(55L), Set.of(23L)), 23, "approval", 1);

            assertEquals(1, result.candidates().size());
            assertTrue(body.get().contains("\"documentId\":23"));
            assertTrue(body.get().contains("\"indexBuildId\":[31]"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void policyUsesStrictJsonAndFallsBackForInvalidSourceIds() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("userModel", provider("{\"type\":\"ACTION\",\"action\":\"OPEN_NODE\","
                + "\"arguments\":{\"nodeId\":999}}"));
        LlmAgentPolicy policy = new LlmAgentPolicy(new ObjectMapper(), beans.getBeanProvider(UserModelProvider.class),
                new AgentDecisionValidator(), 12_000, new SimpleMeterRegistry());

        AgentDecision decision = policy.decide(policyInput());

        assertEquals(RetrievalActionName.SEARCH_KNOWLEDGE, decision.action());
        assertFalse(policy.promptFor(policyInput()).contains("chain_of_thought"));
    }

    @Test
    void agenticLoopSynthesizesExactlyOnceFromSufficientEvidence() {
        Evidence primary = evidence("primary", 55, true, "answer source");
        RetrievalObservation observation = observation(primary);
        RetrievalActionExecutor executor = executor(RetrievalActionName.SEARCH_KNOWLEDGE, observation);
        RetrievalActionRegistry actions = new RetrievalActionRegistry(List.of(executor));
        var policy = mock(LlmAgentPolicy.class);
        when(policy.decide(any(AgentPolicyInput.class))).thenReturn(AgentDecision.action(
                RetrievalActionName.SEARCH_KNOWLEDGE, Map.of("query", "question", "limit", 1)));
        AnswerSynthesizer synthesizer = mock(AnswerSynthesizer.class);
        when(synthesizer.synthesize(any(), any(), any(), any(), any())).thenReturn(
                new AnswerSynthesizer.AnswerDraft("final answer", "prompt", "context", "test", "", false));
        AgenticRetrievalOrchestrator orchestrator = orchestrator(actions, policy, synthesizer);

        var result = orchestrator.execute(new QaRequest(7, "question", null, "user", Set.of()), "exec-1");

        assertEquals("final answer", result.answer());
        assertFalse(result.refused());
        assertEquals(55L, result.citations().get(0).nodeId());
        verify(synthesizer).synthesize(any(), any(), any(), any(), any());
    }

    @Test
    void insufficientEvidenceAndCancellationMakeZeroSynthesisCalls() {
        RetrievalObservation empty = new RetrievalObservation(RetrievalActionName.SEARCH_KNOWLEDGE,
                "empty", List.of(), List.of(), List.of(), 1);
        RetrievalActionRegistry actions = new RetrievalActionRegistry(List.of(
                executor(RetrievalActionName.SEARCH_KNOWLEDGE, empty)));
        var policy = mock(LlmAgentPolicy.class);
        when(policy.decide(any(AgentPolicyInput.class))).thenReturn(AgentDecision.finish());
        AnswerSynthesizer synthesizer = mock(AnswerSynthesizer.class);
        AgenticRetrievalOrchestrator orchestrator = orchestrator(actions, policy, synthesizer);
        QaRequest request = new QaRequest(7, "question", null, "user", Set.of());

        var insufficient = orchestrator.execute(request, "exec-2");
        var cancelled = orchestrator.execute(request, "exec-3", () -> true);

        assertTrue(insufficient.refused());
        assertTrue(cancelled.refused());
        assertEquals(AnswerSynthesizer.INSUFFICIENT_EVIDENCE, insufficient.answer());
        verify(synthesizer, never()).synthesize(any(), any(), any(), any(), any());
    }

    private AgenticRetrievalOrchestrator orchestrator(RetrievalActionRegistry actions,
            LlmAgentPolicy policy, AnswerSynthesizer synthesizer) {
        return new AgenticRetrievalOrchestrator(actions, policy, new AgentDecisionValidator(),
                new EvidenceSelector(16), new EvidenceSufficiencyPolicy(), synthesizer,
                mock(ContextAssembler.class), new SimpleMeterRegistry(), 4, 5_000, 4, 12, 20);
    }

    private RetrievalActionExecutor executor(RetrievalActionName action, RetrievalObservation observation) {
        return new RetrievalActionExecutor() {
            @Override public RetrievalActionName action() { return action; }
            @Override public Set<String> allowedArguments() { return Set.of("query", "limit"); }
            @Override public RetrievalObservation execute(RetrievalActionRequest request,
                    RetrievalToolContext context) { return observation; }
        };
    }

    private RetrievalToolContext context(Set<Long> nodes, Set<Long> documents) {
        return new RetrievalToolContext("exec", "user", 7, null, "question", nodes, documents, 5, 5);
    }

    private RetrievalActionRequest request(RetrievalActionName action, Object... values) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) arguments.put(String.valueOf(values[i]), values[i + 1]);
        return new RetrievalActionRequest(action, arguments);
    }

    private AgentPolicyInput policyInput() {
        return new AgentPolicyInput("user", "question", List.of(), List.of(),
                new EvidenceSufficiency(false, 0, "no-primary-evidence", 0), Set.of(), Set.of(),
                0, 3, 2, 2);
    }

    private UserModelProvider provider(String output) {
        return new UserModelProvider() {
            @Override public String generate(String userId, String prompt) { return output; }
            @Override public boolean configured(String userId) { return true; }
        };
    }

    private RetrievalObservation observation(Evidence evidence) {
        RetrievalObservationItem item = new RetrievalObservationItem(evidence.retrievalUnitId(), evidence.nodeId(),
                evidence.documentId(), evidence.documentVersionId(), evidence.indexBuildId(), evidence.titlePath(),
                evidence.nodeType(), evidence.origin(), evidence.score(), evidence.content(), 1, 1);
        return new RetrievalObservation(RetrievalActionName.SEARCH_KNOWLEDGE, "one", List.of(item),
                List.of(evidence), List.of(), 1);
    }

    private Evidence evidence(String id, long nodeId, boolean primary, String content) {
        return new Evidence(id, 7, 23, 29, nodeId, primary ? 101L : null, 31L, "document.docx",
                primary ? EvidenceOrigin.RETRIEVAL : EvidenceOrigin.NEXT,
                primary ? com.modelrag.knowledge.model.RetrievalUnitType.PARAGRAPH : null,
                NodeType.PARAGRAPH, "Policy", content, new EvidenceLocator("Policy", 1, 1, 0L,
                (long) content.length()), .9, primary ? RetrievalChannel.SEMANTIC : null, primary, Map.of());
    }

    private DocumentNode node(long id, long documentId, long versionId, String content) {
        return new DocumentNode(id, 7, documentId, versionId, null, NodeType.PARAGRAPH, 0, 0,
                "Reference", content, "hash", 1, 1, 0L, (long) content.length(), 1, true, Map.of(), Instant.now());
    }
}
