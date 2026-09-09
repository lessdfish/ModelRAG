package com.modelrag.agent.orchestrator;

import com.modelrag.agent.policy.AgentDecision;
import com.modelrag.agent.policy.AgentDecisionType;
import com.modelrag.agent.policy.AgentDecisionValidator;
import com.modelrag.agent.policy.AgentPolicyInput;
import com.modelrag.agent.policy.LlmAgentPolicy;
import com.modelrag.agent.retrieval.RetrievalActionRegistry;
import com.modelrag.agent.retrieval.RetrievalActionRequest;
import com.modelrag.agent.retrieval.RetrievalObservation;
import com.modelrag.agent.retrieval.RetrievalToolContext;
import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.evidence.AnswerSynthesizer;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceSelector;
import com.modelrag.qa.evidence.EvidenceSet;
import com.modelrag.qa.evidence.EvidenceSufficiency;
import com.modelrag.qa.evidence.EvidenceSufficiencyPolicy;
import com.modelrag.qa.orchestrator.ContextAssembler;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Bounded read-only agentic retrieval; it synthesizes only from the final EvidenceSet. */
@Service
@Profile("test")
public class AgenticRetrievalOrchestrator {
    private final RetrievalActionRegistry actions;
    private final LlmAgentPolicy policy;
    private final AgentDecisionValidator validator;
    private final EvidenceSelector evidenceSelector;
    private final EvidenceSufficiencyPolicy sufficiencyPolicy;
    private final AnswerSynthesizer synthesizer;
    private final ContextAssembler contexts;
    private final MeterRegistry metrics;
    private final int maxSteps;
    private final long deadlineMs;
    private final int maxSearchActions;
    private final int maxNavigationActions;
    private final int maxObservationItems;

    public AgenticRetrievalOrchestrator(RetrievalActionRegistry actions, LlmAgentPolicy policy,
            AgentDecisionValidator validator, EvidenceSelector evidenceSelector,
            EvidenceSufficiencyPolicy sufficiencyPolicy, AnswerSynthesizer synthesizer,
            ContextAssembler contexts, MeterRegistry metrics) {
        this(actions, policy, validator, evidenceSelector, sufficiencyPolicy, synthesizer, contexts, metrics,
                6, 10_000, 4, 12, 20);
    }

    @Autowired
    public AgenticRetrievalOrchestrator(RetrievalActionRegistry actions, LlmAgentPolicy policy,
            AgentDecisionValidator validator, EvidenceSelector evidenceSelector,
            EvidenceSufficiencyPolicy sufficiencyPolicy, AnswerSynthesizer synthesizer,
            ContextAssembler contexts, MeterRegistry metrics,
            @Value("${modelrag.agent.max-steps:6}") int maxSteps,
            @Value("${modelrag.agent.deadline-ms:10000}") long deadlineMs,
            @Value("${modelrag.agent.retrieval.max-search-actions:4}") int maxSearchActions,
            @Value("${modelrag.agent.retrieval.max-navigation-actions:12}") int maxNavigationActions,
            @Value("${modelrag.agent.retrieval.max-observation-items:20}") int maxObservationItems) {
        this.actions = actions;
        this.policy = policy;
        this.validator = validator;
        this.evidenceSelector = evidenceSelector;
        this.sufficiencyPolicy = sufficiencyPolicy;
        this.synthesizer = synthesizer;
        this.contexts = contexts;
        this.metrics = metrics;
        this.maxSteps = Math.max(1, Math.min(10, maxSteps));
        this.deadlineMs = Math.max(500, Math.min(60_000, deadlineMs));
        this.maxSearchActions = Math.max(0, Math.min(32, maxSearchActions));
        this.maxNavigationActions = Math.max(0, Math.min(64, maxNavigationActions));
        this.maxObservationItems = Math.max(1, Math.min(RetrievalObservation.MAX_ITEMS, maxObservationItems));
    }

    public AgenticRetrievalResult execute(QaRequest request, String executionId) {
        return execute(request, executionId, () -> false, AgenticRetrievalEventSink.NOOP);
    }

    public AgenticRetrievalResult execute(QaRequest request, String executionId, BooleanSupplier cancelled) {
        return execute(request, executionId, cancelled, AgenticRetrievalEventSink.NOOP);
    }

    public AgenticRetrievalResult execute(QaRequest request, String executionId, BooleanSupplier cancelled,
            AgenticRetrievalEventSink eventSink) {
        if (request == null || executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("request/executionId is required");
        }
        AgenticRetrievalEventSink events = eventSink == null ? AgenticRetrievalEventSink.NOOP : eventSink;
        String traceId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        RetrievalToolContext context = new RetrievalToolContext(executionId, request.userId(), request.datasetId(),
                request.conversationId(), request.query(), maxSteps, maxSearchActions, maxNavigationActions);
        List<RetrievalObservation> observations = new ArrayList<>();
        List<Evidence> accumulated = new ArrayList<>();
        List<String> steps = new ArrayList<>(List.of("PLAN"));
        LinkedHashSet<String> degraded = new LinkedHashSet<>();
        ConversationContext conversation = conversation(request);
        boolean stopped = false;
        String stopStatus = "DONE";

        while (context.remainingSteps() > 0) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                degraded.add("cancelled");
                steps.add("CANCELLED");
                stopped = true;
                stopStatus = "CANCELLED";
                break;
            }
            if (deadlineExceeded(started)) {
                degraded.add("deadline");
                steps.add("TIMEOUT");
                stopped = true;
                stopStatus = "TIMEOUT";
                break;
            }
            List<Evidence> selected = evidenceSelector.select(accumulated);
            EvidenceSufficiency sufficiency = sufficiencyPolicy.evaluate(request.query(), selected);

            AgentPolicyInput input = new AgentPolicyInput(request.userId(), request.query(), observations, selected,
                    sufficiency, context.observedNodeIds(), context.observedDocumentIds(), context.consumedSteps(),
                    context.remainingSteps(), context.remainingSearchActions(), context.remainingNavigationActions());
            AgentDecision decision = policy.decide(input);
            if (decision == null || decision.type() == AgentDecisionType.FINISH) {
                steps.add("FINISH");
                break;
            }
            try {
                AgentDecision validated = validator.validate(decision, input);
                int actionStep = context.consumedSteps() + 1;
                event(events, "PLAN", "已生成检索动作计划", Map.of(
                        "step", actionStep, "action", validated.action().name(),
                        "remainingSteps", context.remainingSteps(),
                        "remainingSearchActions", context.remainingSearchActions(),
                        "remainingNavigationActions", context.remainingNavigationActions()));
                event(events, "ACT", "正在执行只读检索动作", Map.of(
                        "step", actionStep, "action", validated.action().name(),
                        "arguments", safeArguments(validated.arguments())));
                long actionStarted = System.nanoTime();
                RetrievalObservation observation = actions.execute(new RetrievalActionRequest(validated.action(),
                        validated.arguments()), context);
                if (observation != null) {
                    observations.add(observation);
                    if (observations.size() > AgentPolicyInput.MAX_OBSERVATIONS) observations.remove(0);
                    accumulated.addAll(observation.newEvidence().stream().limit(maxObservationItems).toList());
                    context.observe(observation);
                    degraded.addAll(observation.degradedComponents());
                    event(events, "OBSERVE", "检索动作已返回观察结果", Map.of(
                            "step", actionStep, "action", validated.action().name(),
                            "itemCount", observation.items().size(),
                            "newEvidenceCount", observation.newEvidence().size(),
                            "latencyMs", observation.latencyMs(),
                            "degradedComponents", observation.degradedComponents().stream().limit(8).toList()));
                    steps.add("ACT:" + validated.action().name());
                    steps.add("OBSERVE");
                    metrics.counter("modelrag.agent.retrieval.actions", "action", validated.action().name()).increment();
                } else {
                    event(events, "OBSERVE", "检索动作未返回观察结果", Map.of(
                            "step", actionStep, "action", validated.action().name(),
                            "itemCount", 0, "newEvidenceCount", 0,
                            "latencyMs", elapsed(actionStarted),
                            "degradedComponents", List.of("empty-observation")));
                }
            } catch (RuntimeException error) {
                degraded.add("invalid-retrieval-action");
                event(events, "OBSERVE", "检索动作执行失败", Map.of(
                        "action", decision.action() == null ? "INVALID" : decision.action().name(), "itemCount", 0,
                        "newEvidenceCount", 0, "latencyMs", 0,
                        "degradedComponents", List.of("invalid-retrieval-action")));
                steps.add("INVALID_ACTION");
                stopped = true;
                stopStatus = "ERROR";
                break;
            }
        }

        if (!stopped && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
            degraded.add("cancelled");
            steps.add("CANCELLED");
            stopped = true;
            stopStatus = "CANCELLED";
        }
        if (!stopped && deadlineExceeded(started)) {
            degraded.add("deadline");
            steps.add("TIMEOUT");
            stopped = true;
            stopStatus = "TIMEOUT";
        }
        List<Evidence> selected = evidenceSelector.select(accumulated);
        EvidenceSufficiency sufficiency = sufficiencyPolicy.evaluate(request.query(), selected);
        if (!stopped && context.remainingSteps() <= 0 && !sufficiency.sufficient()) {
            degraded.add("step-budget");
            steps.add("MAX_STEPS");
        }
        metrics.counter("modelrag.agent.retrieval.steps").increment(context.consumedSteps());
        EvidenceSet evidenceSet = new EvidenceSet(traceId, request.query(), selected, sufficiency,
                List.copyOf(degraded),
                elapsed(started), context.consumedNavigationActions());
        if (stopped || !sufficiency.sufficient()) {
            metrics.counter("modelrag.agent.retrieval.refused").increment();
            return new AgenticRetrievalResult(executionId, stopped ? stopStatus : "DONE",
                    AnswerSynthesizer.INSUFFICIENT_EVIDENCE, List.of(), sufficiency.confidence(), true,
                    traceId, steps, evidenceSet.degradedComponents());
        }

        AnswerSynthesizer.AnswerDraft draft = synthesizer.synthesize(request.userId(), request.query(), conversation,
                evidenceSet, null);
        metrics.counter("modelrag.agent.retrieval.synthesis.calls").increment();
        List<Citation> citations = selected.stream().filter(Evidence::primary).map(Citation::fromEvidence).toList();
        steps.add("ANSWER");
        metrics.counter("modelrag.agent.retrieval.completed").increment();
        return new AgenticRetrievalResult(executionId, "DONE", draft.answer(), citations,
                sufficiency.confidence(), false, traceId, steps, evidenceSet.degradedComponents());
    }

    private ConversationContext conversation(QaRequest request) {
        try {
            return contexts.build(request, request.query()).conversation();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private boolean deadlineExceeded(long started) {
        return System.nanoTime() - started >= deadlineMs * 1_000_000;
    }

    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private void event(AgenticRetrievalEventSink sink, String type, String message, Map<String, Object> data) {
        try {
            sink.publish(type, message, data);
        } catch (RuntimeException error) {
            if (metrics != null) metrics.counter("modelrag.agent.retrieval.event.failures", "type", type).increment();
        }
    }

    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (arguments == null) return safe;
        arguments.forEach((key, value) -> {
            if ("query".equals(key)) {
                safe.put("queryChars", value instanceof String text ? text.length() : 0);
            } else if (value instanceof Number || value instanceof Boolean) {
                safe.put(key, value);
            } else {
                safe.put(key, "present");
            }
        });
        return Map.copyOf(safe);
    }

}
