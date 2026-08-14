package com.modelrag.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.api.UserModelProvider;
import com.modelrag.common.model.ModelGateway;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class AgentPlanner {
    public record Plan(String toolName, List<String> subtasks, String mode) {
    }

    private final ObjectProvider<ModelGateway> models;
    private final ObjectProvider<UserModelProvider> userModels;
    private final ObjectMapper json;

    public AgentPlanner(ObjectProvider<ModelGateway> models, ObjectProvider<UserModelProvider> userModels,
                        ObjectMapper json) {
        this.models = models;
        this.userModels = userModels;
        this.json = json;
    }

    public Plan reactStep(String query, List<String> observations, String fallbackTool, List<String> fallbackSubtasks,
                          List<ToolDefinition> tools, int stepIndex) {
        return reactStep(null, query, observations, fallbackTool, fallbackSubtasks, tools, stepIndex);
    }

    public Plan reactStep(String userId, String query, List<String> observations, String fallbackTool,
                          List<String> fallbackSubtasks, List<ToolDefinition> tools, int stepIndex) {
        String generated = null;
        UserModelProvider userModel = userModels.getIfAvailable();
        if (userId != null && !userId.isBlank() && userModel != null && userModel.configured(userId)) {
            generated = userModel.generate(userId, reactPrompt(query, observations, tools, stepIndex));
        } else if (userId == null) {
            // Compatibility path for non-request callers and existing tests. User-scoped requests
            // fall back to deterministic bounded planning instead of a platform model.
            ModelGateway gateway = models.getIfAvailable();
            if (gateway != null) generated = gateway.generate(reactPrompt(query, observations, tools, stepIndex));
        }
        if (generated == null) return fallbackStep(fallbackTool, fallbackSubtasks, stepIndex);
        try {
            generated = generated.trim();
            if (generated.startsWith("[mock]") || generated.startsWith("[fallback]")) {
                return fallbackStep(fallbackTool, fallbackSubtasks, stepIndex);
            }
            JsonNode node = json.readTree(jsonObject(generated));
            if (node.path("done").asBoolean(false)) {
                return new Plan(fallbackTool, List.of(), "LLM_REACT");
            }
            String toolName = validTool(node.path("tool").asText(fallbackTool), fallbackTool, tools);
            String subtask = node.path("subtask").asText(node.path("query").asText("")).trim();
            if (subtask.isBlank() && node.path("subtasks").isArray() && node.path("subtasks").size() > 0) {
                subtask = node.path("subtasks").get(0).asText("").trim();
            }
            if (subtask.isBlank()) return fallbackStep(toolName, fallbackSubtasks, stepIndex);
            return new Plan(toolName, List.of(subtask), "LLM_REACT");
        } catch (Exception ignored) {
            return fallbackStep(fallbackTool, fallbackSubtasks, stepIndex);
        }
    }

    private Plan fallbackStep(String fallbackTool, List<String> fallbackSubtasks, int stepIndex) {
        List<String> subtasks = fallbackSubtasks == null ? List.of() : fallbackSubtasks.stream().filter(task -> !task.isBlank()).toList();
        if (stepIndex >= subtasks.size()) return new Plan(fallbackTool, List.of(), "RULE_REACT");
        return new Plan(fallbackTool, List.of(subtasks.get(stepIndex)), "RULE_REACT");
    }

    private String validTool(String requested, String fallbackTool, List<ToolDefinition> tools) {
        return tools.stream()
                .map(ToolDefinition::name)
                .filter(name -> Objects.equals(name, requested))
                .findFirst()
                .orElse(fallbackTool);
    }

    private String reactPrompt(String query, List<String> observations, List<ToolDefinition> tools, int stepIndex) {
        String toolText = tools.stream()
                .map(tool -> "- " + tool.name() + " risk=" + tool.riskLevel() + " desc=" + tool.description())
                .reduce("", (left, right) -> left + right + "\n");
        String observationText = observations == null || observations.isEmpty()
                ? "无"
                : observations.stream().limit(20).reduce("", (left, right) -> left + "- " + right + "\n");
        return """
                你是企业知识库 Agent 的 ReAct 控制器。每一步只输出 JSON，不要解释。
                可用工具:
                %s
                原始问题: %s
                当前步数: %d
                已有观察:
                %s
                输出格式:
                1. 继续行动: {"done":false,"tool":"knowledge_lookup","subtask":"一个明确可执行的子问题"}
                2. 已足够回答: {"done":true}
                规则:
                1. 只返回安全的计划字段，不输出内部推理。
                2. 只允许选择可用工具列表中的 tool。
                3. subtask 必须具体、可检索、不可重复已有观察中的查询。
                4. 不要输出自然语言解释，只输出 JSON。
                """.formatted(toolText, query == null ? "" : query, stepIndex + 1, observationText);
    }

    private String jsonObject(String value) {
        String text = value == null ? "" : value.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("planner output is not JSON");
        return text.substring(start, end + 1);
    }
}
