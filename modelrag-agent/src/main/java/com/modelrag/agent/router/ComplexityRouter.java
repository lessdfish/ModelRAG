package com.modelrag.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.model.ModelHealthRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ComplexityRouter {
    private final boolean intentEnabled;
    private final String intentUrl;
    private final ObjectMapper json;
    private final ObjectProvider<ModelHealthRegistry> health;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Autowired
    public ComplexityRouter(@Value("${modelrag.intent.enabled:false}") boolean enabled, @Value("${modelrag.intent.url:http://127.0.0.1:18080}") String url, ObjectMapper json, ObjectProvider<ModelHealthRegistry> health) {
        intentEnabled = enabled;
        intentUrl = url.replaceAll("/$", "");
        this.json = json;
        this.health = health;
    }

    public RouteDecision route(String query) {
        RouteDecision rule = rule(query);
        if (rule == RouteDecision.TOOL_AGENT || !intentEnabled) return rule;
        String modelName = "http-router-" + intentUrl;
        ModelHealthRegistry registry = health.getIfAvailable();
        if (registry != null && !registry.available("ROUTER", modelName)) return rule;
        try {
            String body = json.writeValueAsString(Map.of("query", query == null ? "" : query));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(intentUrl + "/classify")).timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() / 100 == 2) {
                registrySuccess(registry, modelName);
                double confidence = result.path("confidence").asDouble(Double.NaN);
                RouteDecision modelRoute = modelRoute(result.path("label").asText());
                if (modelRoute != null && Double.isFinite(confidence) && confidence >= .8 && confidence <= 1.0) {
                    return modelRoute;
                }
            } else registryFailure(registry, modelName);
        } catch (Exception ignored) {
            registryFailure(registry, modelName);
        }
        return rule;
    }

    private void registrySuccess(ModelHealthRegistry registry, String modelName) {
        if (registry != null) registry.success("ROUTER", modelName);
    }

    private void registryFailure(ModelHealthRegistry registry, String modelName) {
        if (registry != null) registry.failure("ROUTER", modelName);
    }

    private RouteDecision rule(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (containsBusinessIntent(q)) return RouteDecision.TOOL_AGENT;
        return containsAny(q, "对比", "比较", "差异", "区别", "分别", "跨文档", "引用关系",
                "多个制度汇总", "多条款分析", "根据a再判断b", "汇总", "总结", "整理", "分析",
                "规划", "计划", "方案", "生成", "起草", "制定")
                ? RouteDecision.AGENTIC_RAG : RouteDecision.DIRECT_RAG;
    }

    private boolean containsBusinessIntent(String query) {
        if (containsAny(query, "查订单", "查询订单", "调用", "执行", "删除", "变更", "批准",
                "提交", "创建", "修改")) return true;
        if (containsAny(query, "请审批", "提交审批", "发起审批", "审批这", "审批该")) return true;
        return query.contains("审批") && !containsAny(query, "谁", "什么", "如何", "怎么", "流程",
                "条件", "要求", "规则", "需要", "起草", "方案", "规划", "计划", "总结", "汇总",
                "对比", "比较", "分析");
    }

    private RouteDecision modelRoute(String label) {
        if (label == null) return null;
        return switch (label.trim().toUpperCase(Locale.ROOT)) {
            case "DIRECT_RAG" -> RouteDecision.DIRECT_RAG;
            case "AGENTIC_RAG", "AGENT" -> RouteDecision.AGENTIC_RAG;
            case "TOOL_AGENT" -> RouteDecision.TOOL_AGENT;
            case "HYBRID" -> RouteDecision.AGENTIC_RAG;
            default -> null;
        };
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
