package com.modelrag.agent.runtime;

import com.modelrag.agent.policy.AgentDecision;
import com.modelrag.agent.policy.AgentDecisionType;
import com.modelrag.agent.policy.AgentDecisionValidator;
import com.modelrag.agent.policy.AgentPolicyInput;
import com.modelrag.agent.policy.LlmAgentPolicy;
import com.modelrag.agent.retrieval.RetrievalActionName;
import com.modelrag.agent.retrieval.RetrievalActionRegistry;
import com.modelrag.agent.retrieval.RetrievalActionRequest;
import com.modelrag.agent.retrieval.RetrievalObservation;
import com.modelrag.agent.retrieval.RetrievalToolContext;
import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.evidence.AnswerSynthesizer;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceLocator;
import com.modelrag.qa.evidence.EvidenceOrigin;
import com.modelrag.qa.evidence.EvidenceSelector;
import com.modelrag.qa.evidence.EvidenceSet;
import com.modelrag.qa.evidence.EvidenceSufficiency;
import com.modelrag.qa.evidence.EvidenceSufficiencyPolicy;
import com.modelrag.qa.orchestrator.ContextAssembler;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.search.dto.RetrievalChannel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Durable one-transition implementation of the G7 read-only retrieval state machine. */
@Service
@Profile("!test")
public class AgenticRagModeHandler implements AgentModeHandler {
    private final RetrievalActionRegistry actions;
    private final LlmAgentPolicy policy;
    private final AgentDecisionValidator validator;
    private final EvidenceSelector evidenceSelector;
    private final EvidenceSufficiencyPolicy sufficiencyPolicy;
    private final AnswerSynthesizer synthesizer;
    private final ContextAssembler contexts;
    private final MeterRegistry metrics;
    private final int maxObservationItems;

    public AgenticRagModeHandler(RetrievalActionRegistry actions, LlmAgentPolicy policy,
            AgentDecisionValidator validator, EvidenceSelector evidenceSelector,
            EvidenceSufficiencyPolicy sufficiencyPolicy, AnswerSynthesizer synthesizer,
            ContextAssembler contexts, MeterRegistry metrics,
            @Value("${modelrag.agent.retrieval.max-observation-items:20}") int maxObservationItems) {
        this.actions = actions;
        this.policy = policy;
        this.validator = validator;
        this.evidenceSelector = evidenceSelector;
        this.sufficiencyPolicy = sufficiencyPolicy;
        this.synthesizer = synthesizer;
        this.contexts = contexts;
        this.metrics = metrics;
        this.maxObservationItems = Math.max(1, Math.min(RetrievalObservation.MAX_ITEMS, maxObservationItems));
    }

    @Override public String mode() { return "AGENTIC_RAG"; }

    @Override
    public AgentModeDecision decide(AgentState state) {
        List<Evidence> selected = select(state);
        EvidenceSufficiency sufficiency = sufficiencyPolicy.evaluate(state.goal(), selected);
        AgentPolicyInput input = new AgentPolicyInput(state.userId(), state.goal(), observations(state), selected,
                sufficiency, observedNodes(state), observedDocuments(state), state.currentStep(),
                state.budgets().remainingSteps(), state.budgets().remainingSearchActions(),
                state.budgets().remainingNavigationActions());
        AgentDecision decision = policy.decide(input);
        if (decision == null || decision.type() == AgentDecisionType.FINISH) {
            if (!sufficiency.sufficient()) {
                return AgentModeDecision.terminal(result(state, AnswerSynthesizer.INSUFFICIENT_EVIDENCE,
                        List.of(), sufficiency.confidence(), true));
            }
            return AgentModeDecision.action(new AgentPendingAction(actionId(state),
                    AgentPendingActionKind.FINAL_SYNTHESIS, "FINAL_SYNTHESIS", Map.of(),
                    actionId(state), false, true, Instant.now()));
        }
        AgentDecision validated = validator.validate(decision, input);
        boolean readOnly = validated.action() != RetrievalActionName.FINISH;
        return AgentModeDecision.action(new AgentPendingAction(actionId(state),
                AgentPendingActionKind.RETRIEVAL, validated.action().name(), validated.arguments(),
                actionId(state), false, readOnly, Instant.now()));
    }

    @Override
    public AgentState execute(AgentState state, AgentPendingAction action) {
        if (action.kind() == AgentPendingActionKind.FINAL_SYNTHESIS) return synthesize(state);
        if (action.kind() != AgentPendingActionKind.RETRIEVAL) {
            throw new IllegalArgumentException("AGENTIC_RAG cannot execute a business action");
        }
        RetrievalActionName name;
        try {
            name = RetrievalActionName.valueOf(action.actionName());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("unknown retrieval action", error);
        }
        RetrievalToolContext context = context(state);
        RetrievalObservation observation = actions.execute(new RetrievalActionRequest(name, action.arguments()), context);
        context.observe(observation);
        AgentBudgetState budgets = state.budgets().consumeStep();
        if (isSearch(name)) budgets = budgets.consumeSearch();
        if (isNavigation(name)) budgets = budgets.consumeNavigation();
        List<AgentEvidenceSnapshot> evidence = new ArrayList<>(state.evidence());
        observation.newEvidence().stream().limit(maxObservationItems)
                .map(AgenticRagModeHandler::snapshot).forEach(evidence::add);
        List<AgentObservedSource> sources = new ArrayList<>(state.observedSources());
        observation.items().forEach(item -> addSource(sources, item.nodeId(), item.documentId(),
                item.documentVersionId(), item.indexBuildId(), "", item.titlePath(),
                item.nodeType() == null ? "" : item.nodeType().name(), item.score()));
        observation.newEvidence().forEach(item -> addSource(sources, item.nodeId(), item.documentId(),
                item.documentVersionId(), item.indexBuildId(), item.documentName(), item.titlePath(),
                item.nodeType() == null ? "" : item.nodeType().name(), item.score()));
        List<AgentObservationSnapshot> observations = new ArrayList<>(state.observations());
        observations.add(new AgentObservationSnapshot(name.name(), observation.summary(), observation.items().size(),
                observation.newEvidence().size(), observation.degradedComponents(), observation.latencyMs()));
        if (observations.size() > AgentState.MAX_OBSERVATIONS) {
            observations = new ArrayList<>(observations.subList(observations.size() - AgentState.MAX_OBSERVATIONS,
                    observations.size()));
        }
        List<String> degraded = merge(state.degradedComponents(), observation.degradedComponents());
        Map<String, Object> toolState = withSteps(state.toolState(), List.of("ACT:" + name.name(), "OBSERVE"));
        return state.toBuilder().currentStep(state.currentStep() + 1).budgets(budgets)
                .observedSources(sources).observations(observations).evidence(evidence)
                .degradedComponents(degraded).toolState(toolState).pendingAction(null).build();
    }

    private AgentState synthesize(AgentState state) {
        List<Evidence> selected = select(state);
        EvidenceSufficiency sufficiency = sufficiencyPolicy.evaluate(state.goal(), selected);
        if (!sufficiency.sufficient()) {
            return state.toBuilder().status(AgentRuntimeStatus.DONE).pendingAction(null)
                    .result(result(state, AnswerSynthesizer.INSUFFICIENT_EVIDENCE, List.of(),
                            sufficiency.confidence(), true)).build();
        }
        String traceId = stringValue(state.toolState().get("traceId"));
        if (traceId.isBlank()) traceId = UUID.randomUUID().toString();
        EvidenceSet set = new EvidenceSet(traceId, state.goal(), selected, sufficiency,
                state.degradedComponents(), 0, state.currentStep());
        QaRequest request = new QaRequest(state.datasetId(), state.goal(), state.conversationId(),
                state.userId(), state.userRoles());
        ConversationContext conversation = null;
        try { conversation = contexts.build(request, state.goal()).conversation(); }
        catch (RuntimeException ignored) { }
        AnswerSynthesizer.AnswerDraft draft = synthesizer.synthesize(state.userId(), state.goal(), conversation, set, null);
        List<com.modelrag.qa.dto.Citation> citations = selected.stream().filter(Evidence::primary)
                .map(com.modelrag.qa.dto.Citation::fromEvidence).toList();
        return state.toBuilder().status(AgentRuntimeStatus.DONE).pendingAction(null)
                .result(result(state, draft.answer(), citations, sufficiency.confidence(), false))
                .toolState(withSteps(state.toolState(), List.of("ANSWER"))).build();
    }

    private AgentResultSnapshot result(AgentState state, String answer,
            List<com.modelrag.qa.dto.Citation> citations, double confidence, boolean refused) {
        List<AgentCitationSnapshot> values = citations == null ? List.of() : citations.stream().limit(16)
                .map(value -> new AgentCitationSnapshot(value.documentId(), value.documentVersionId(), value.nodeId(),
                        value.excerpt(), value.score())).toList();
        return new AgentResultSnapshot("DONE", answer, values, Math.max(0, Math.min(1, confidence)), refused,
                stringValue(state.toolState().get("traceId")), state.degradedComponents());
    }

    private List<Evidence> select(AgentState state) {
        return evidenceSelector.select(state.evidence().stream().map(AgenticRagModeHandler::evidence).toList());
    }

    private RetrievalToolContext context(AgentState state) {
        List<RetrievalToolContext.ObservedSource> sources = state.observedSources().stream()
                .map(value -> new RetrievalToolContext.ObservedSource(value.nodeId(), value.documentId(), value.documentVersionId(),
                        value.indexBuildId(), value.documentName(), value.titlePath(), nodeType(value.nodeType()),
                        value.score())).toList();
        return new RetrievalToolContext(state.executionId(), state.userId(), state.datasetId(), state.conversationId(),
                state.goal(), sources, state.budgets().remainingSteps(), state.budgets().remainingSearchActions(),
                state.budgets().remainingNavigationActions());
    }

    private List<com.modelrag.agent.retrieval.RetrievalObservation> observations(AgentState state) {
        return state.observations().stream().map(value -> {
            RetrievalActionName action;
            try { action = RetrievalActionName.valueOf(value.action()); }
            catch (RuntimeException error) { action = RetrievalActionName.SEARCH_KNOWLEDGE; }
            return new RetrievalObservation(action, value.summary(), List.of(), List.of(),
                    value.degradedComponents(), value.latencyMs());
        }).toList();
    }

    private Set<Long> observedNodes(AgentState state) {
        return state.observedSources().stream().map(AgentObservedSource::nodeId).collect(java.util.stream.Collectors.toSet());
    }

    private Set<Long> observedDocuments(AgentState state) {
        return state.observedSources().stream().map(AgentObservedSource::documentId).collect(java.util.stream.Collectors.toSet());
    }

    private static AgentEvidenceSnapshot snapshot(Evidence value) {
        return new AgentEvidenceSnapshot(value.evidenceId(), value.datasetId(), value.documentId(),
                value.documentVersionId(), value.nodeId(), value.retrievalUnitId(), value.indexBuildId(),
                value.documentName(), value.origin().name(), value.nodeType().name(),
                value.unitType() == null ? "" : value.unitType().name(), value.titlePath(), value.content(),
                value.locator().titlePath(), value.score(), value.channel() == null ? "" : value.channel().name(),
                value.primary());
    }

    private static void addSource(List<AgentObservedSource> sources, long nodeId, long documentId,
            long versionId, Long buildId, String documentName, String titlePath, String nodeType, double score) {
        if (sources.stream().noneMatch(old -> old.nodeId() == nodeId)) {
            sources.add(new AgentObservedSource(nodeId, documentId, versionId, buildId, documentName,
                    titlePath, nodeType, score));
        }
    }

    private static Evidence evidence(AgentEvidenceSnapshot value) {
        return new Evidence(value.evidenceId(), value.datasetId(), value.documentId(), value.documentVersionId(),
                value.nodeId(), value.retrievalUnitId(), value.indexBuildId(), value.documentName(), origin(value.origin()),
                unitType(value.unitType()), nodeType(value.nodeType()), value.titlePath(), value.boundedContentOrExcerpt(),
                EvidenceLocator.empty(), value.score(), channel(value.channel()), value.primary(), Map.of());
    }

    private static EvidenceOrigin origin(String value) {
        try { return EvidenceOrigin.valueOf(value); } catch (RuntimeException error) { return EvidenceOrigin.RETRIEVAL; }
    }

    private static RetrievalUnitType unitType(String value) {
        try { return value == null || value.isBlank() ? null : RetrievalUnitType.valueOf(value); }
        catch (RuntimeException error) { return null; }
    }

    private static NodeType nodeType(String value) {
        try { return value == null || value.isBlank() ? NodeType.PARAGRAPH : NodeType.valueOf(value); }
        catch (RuntimeException error) { return NodeType.PARAGRAPH; }
    }

    private static RetrievalChannel channel(String value) {
        try { return value == null || value.isBlank() ? null : RetrievalChannel.valueOf(value); }
        catch (RuntimeException error) { return null; }
    }

    private static boolean isSearch(RetrievalActionName action) {
        return action == RetrievalActionName.SEARCH_KNOWLEDGE || action == RetrievalActionName.FIND_IN_DOCUMENT;
    }

    private static boolean isNavigation(RetrievalActionName action) {
        return action != RetrievalActionName.SEARCH_KNOWLEDGE && action != RetrievalActionName.FIND_IN_DOCUMENT;
    }

    private static String actionId(AgentState state) {
        return state.executionId() + ":action:" + (state.currentStep() + 1);
    }

    private static List<String> merge(List<String> left, List<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (left != null) values.addAll(left);
        if (right != null) values.addAll(right);
        return values.stream().limit(16).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withSteps(Map<String, Object> source, List<String> additions) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        if (source != null) result.putAll(source);
        List<String> steps = new ArrayList<>();
        Object old = result.get("steps");
        if (old instanceof List<?> values) {
            for (Object value : values) if (value != null) steps.add(String.valueOf(value));
        }
        if (steps.isEmpty()) steps.add("PLAN");
        if (additions != null) steps.addAll(additions);
        if (steps.size() > 64) steps = new ArrayList<>(steps.subList(steps.size() - 64, steps.size()));
        result.put("steps", steps);
        return result;
    }

    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
}
