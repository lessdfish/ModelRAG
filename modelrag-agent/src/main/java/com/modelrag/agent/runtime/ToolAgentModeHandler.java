package com.modelrag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.orchestrator.AgentPlanner;
import com.modelrag.agent.safety.LoopDetector;
import com.modelrag.agent.tool.HttpToolInvoker;
import com.modelrag.agent.tool.ResilientToolExecutor;
import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.agent.tool.ToolRegistry;
import com.modelrag.agent.trace.ToolCallTrace;
import com.modelrag.agent.trace.ToolCallTracer;
import com.modelrag.common.operation.OperationGuard;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Durable one-tool transition for the existing safeguarded TOOL_AGENT path. */
@Service
@Profile("!test")
public class ToolAgentModeHandler implements AgentModeHandler {
    private final AgentPlanner planner;
    private final ToolRegistry tools;
    private final ResilientToolExecutor executor;
    private final HttpToolInvoker httpTools;
    private final QaOrchestrator qa;
    private final ToolCallTracer tracer;
    private final LoopDetector loops;
    private final ObjectMapper json;
    private final OperationGuard operationGuard;

    public ToolAgentModeHandler(AgentPlanner planner, ToolRegistry tools, ResilientToolExecutor executor,
            HttpToolInvoker httpTools, QaOrchestrator qa, ToolCallTracer tracer, LoopDetector loops,
            ObjectMapper json, OperationGuard operationGuard) {
        this.planner = planner;
        this.tools = tools;
        this.executor = executor;
        this.httpTools = httpTools;
        this.qa = qa;
        this.tracer = tracer;
        this.loops = loops;
        this.json = json;
        this.operationGuard = operationGuard;
    }

    @Override public String mode() { return "TOOL_AGENT"; }

    @Override
    public AgentModeDecision decide(AgentState state) {
        if (state.budgets().remainingSteps() <= 0) {
            return AgentModeDecision.terminal(finalResult(state));
        }
        String fallbackTool = string(state.toolState().get("fallbackTool"));
        List<String> parts = strings(state.toolState().get("parts"));
        List<String> observations = strings(state.toolState().get("observations"));
        AgentPlanner.Plan decision = planner.reactStep(state.userId(), state.goal(), observations, fallbackTool,
                parts, availableTools(state, fallbackTool), state.currentStep());
        if (decision.subtasks().isEmpty()) return AgentModeDecision.terminal(finalResult(state));
        String toolName = decision.toolName();
        String subtask = decision.subtasks().get(0);
        if ("knowledge_lookup".equals(toolName) && !"knowledge_lookup".equals(fallbackTool)) {
            return AgentModeDecision.terminal(unresolved(state));
        }
        ToolDefinition definition;
        try { definition = tools.get(toolName); }
        catch (RuntimeException error) { return AgentModeDecision.terminal(unresolved(state)); }
        String fingerprint = normalize(toolName + ":" + subtask);
        List<String> fingerprints = strings(state.toolState().get("actionFingerprints"));
        if (loops.detect(new ArrayList<>(append(fingerprints, fingerprint)))) {
            return AgentModeDecision.terminal(finalResult(state));
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", subtask);
        arguments.put("datasetId", state.datasetId());
        arguments.put("userId", state.userId());
        if (state.conversationId() != null) arguments.put("conversationId", state.conversationId());
        String actionId = state.executionId() + ":action:" + (state.currentStep() + 1);
        boolean safeReplay = definition.idempotent()
                || ("LOW".equalsIgnoreCase(definition.riskLevel()) && !definition.http());
        return AgentModeDecision.action(new AgentPendingAction(actionId, AgentPendingActionKind.BUSINESS_TOOL,
                toolName, arguments, actionId, requiresApproval(definition), safeReplay, Instant.now()));
    }

    @Override
    public AgentState execute(AgentState state, AgentPendingAction action) {
        ToolDefinition definition = tools.get(action.actionName());
        if (!canUse(definition, state)) {
            throw new IllegalStateException("用户无权调用工具: " + definition.name());
        }
        if (action.requiresApproval()) {
            if (action.toolIdempotent()) {
                operationGuard.requireAvailableForSideEffect();
            } else {
                operationGuard.claimSideEffect(action.idempotencyKey());
            }
        }
        QaRequest request = new QaRequest(state.datasetId(), string(action.arguments().get("query")),
                state.conversationId(), state.userId(), state.userRoles());
        String params = writeParams(action.arguments());
        long started = System.nanoTime();
        try {
            ResilientToolExecutor.Result<QaResult> result = executor.execute(definition, params,
                    () -> executeTool(definition, request, action.idempotencyKey()));
            QaResult value = result.value();
            tracer.record(new ToolCallTrace(state.executionId(), definition.name(), params,
                    output(value, result), true, null, elapsed(started)));
            Map<String, Object> toolState = appendOutcome(state.toolState(), action, value);
            return state.toBuilder().currentStep(state.currentStep() + 1)
                    .budgets(state.budgets().consumeStep().consumeTool()).toolState(toolState)
                    .pendingAction(null).build();
        } catch (RuntimeException error) {
            tracer.record(new ToolCallTrace(state.executionId(), definition.name(), params, "{}", false,
                    error.getMessage(), elapsed(started)));
            throw error;
        }
    }

    private QaResult executeTool(ToolDefinition definition, QaRequest request, String idempotencyKey) {
        if (definition.http()) {
            return new QaResult("结论：" + limit(httpTools.invoke(definition, request.query(), idempotencyKey), 500),
                    List.of(), 1, false, null);
        }
        return qa.answer(request.withoutConversationMessage());
    }

    private List<ToolDefinition> availableTools(AgentState state, String fallbackTool) {
        return tools.listEnabled().stream()
                .filter(tool -> !"knowledge_lookup".equals(tool.name()) || "knowledge_lookup".equals(fallbackTool))
                .filter(tool -> canUse(tool, state)).toList();
    }

    private boolean canUse(ToolDefinition tool, AgentState state) {
        boolean dataset = tool.allowedDatasetIds() == null || tool.allowedDatasetIds().isEmpty()
                || tool.allowedDatasetIds().contains(state.datasetId());
        Set<String> roles = state.userRoles() == null ? Set.of() : state.userRoles();
        return dataset && (tool.allowedRoles() == null || tool.allowedRoles().isEmpty()
                || roles.stream().anyMatch(tool.allowedRoles()::contains));
    }

    private AgentResultSnapshot finalResult(AgentState state) {
        List<Map<String, Object>> answers = maps(state.toolState().get("answers"));
        List<AgentCitationSnapshot> citations = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        double confidence = 0;
        boolean refused = answers.isEmpty();
        for (Map<String, Object> value : answers) {
            String text = string(value.get("answer"));
            if (!text.isBlank()) {
                if (!answer.isEmpty()) answer.append('\n');
                answer.append(text);
            }
            confidence = Math.max(confidence, number(value.get("confidence")));
            refused &= bool(value.get("refused"));
            Object source = value.get("citations");
            for (Map<String, Object> citation : maps(source)) {
                try {
                    citations.add(new AgentCitationSnapshot((long) number(citation.get("documentId")),
                            (long) number(citation.get("documentVersionId")),
                            (long) number(citation.get("nodeId")), string(citation.get("excerpt")),
                            number(citation.get("score"))));
                } catch (RuntimeException ignored) { }
            }
        }
        if (answer.isEmpty()) answer.append("Agent 未获得可用观察结果。");
        return new AgentResultSnapshot("DONE", answer.toString(), citations, confidence, refused,
                string(state.toolState().get("traceId")), state.degradedComponents());
    }

    private AgentResultSnapshot unresolved(AgentState state) {
        return new AgentResultSnapshot("ERROR", "tool-unresolved", List.of(), 0, true,
                string(state.toolState().get("traceId")), append(state.degradedComponents(), "tool-unresolved"));
    }

    private Map<String, Object> appendOutcome(Map<String, Object> source, AgentPendingAction action, QaResult result) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (source != null) state.putAll(source);
        List<String> observations = new ArrayList<>(strings(state.get("observations")));
        observations.add("query=" + string(action.arguments().get("query")) + " refused=" + result.refused()
                + " traceId=" + string(result.traceId()) + " answer=" + limit(result.answer(), 180));
        state.put("observations", limitList(observations, 20));
        List<Map<String, Object>> answers = new ArrayList<>(maps(state.get("answers")));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("answer", limit(result.answer(), 500)); answer.put("refused", result.refused());
        answer.put("confidence", result.confidence()); answer.put("traceId", string(result.traceId()));
        List<Map<String, Object>> citations = new ArrayList<>();
        result.citations().stream().limit(8).forEach(citation -> citations.add(Map.of(
                "documentId", citation.documentId(), "documentVersionId", value(citation.documentVersionId()),
                "nodeId", value(citation.nodeId()), "excerpt", citation.excerpt(), "score", citation.score())));
        answer.put("citations", citations);
        answers.add(answer); state.put("answers", limitMaps(answers, 16));
        List<String> fingerprints = new ArrayList<>(strings(state.get("actionFingerprints")));
        fingerprints.add(normalize(action.actionName() + ":" + string(action.arguments().get("query"))));
        state.put("actionFingerprints", limitList(fingerprints, 32));
        return state;
    }

    private String writeParams(Map<String, Object> params) {
        try { return json.writeValueAsString(params == null ? Map.of() : params); }
        catch (Exception error) { throw new IllegalArgumentException("tool parameters cannot be serialized", error); }
    }

    private String output(QaResult value, ResilientToolExecutor.Result<QaResult> execution) {
        return "{\"traceId\":\"" + string(value.traceId()) + "\",\"refused\":" + value.refused()
                + ",\"attempts\":" + execution.attempts() + ",\"reused\":" + execution.reused() + "}";
    }

    private boolean requiresApproval(ToolDefinition definition) {
        return "HIGH".equalsIgnoreCase(definition.riskLevel())
                || "EXTERNAL_SIDE_EFFECT".equalsIgnoreCase(definition.riskLevel());
    }

    private static String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(); }
    private static String limit(String value, int max) { String text = string(value); return text.length() <= max ? text : text.substring(0, max) + "…"; }
    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }
    private static boolean bool(Object value) { return value instanceof Boolean flag && flag; }
    private static Object value(Object value) { return value == null ? 0 : value; }

    private static List<String> append(List<String> source, String value) {
        List<String> result = new ArrayList<>(source == null ? List.of() : source);
        result.add(value); return result;
    }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(item -> item != null).map(String::valueOf).limit(20).toList();
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) if (item instanceof Map<?, ?> map) {
            Map<String, Object> row = new LinkedHashMap<>();
            map.forEach((key, val) -> row.put(String.valueOf(key), val)); result.add(row);
        }
        return result;
    }
    private static List<String> limitList(List<String> value, int max) {
        return value.size() <= max ? List.copyOf(value) : List.copyOf(value.subList(value.size() - max, value.size()));
    }
    private static List<Map<String, Object>> limitMaps(List<Map<String, Object>> value, int max) {
        return value.size() <= max ? List.copyOf(value) : List.copyOf(value.subList(value.size() - max, value.size()));
    }
}
