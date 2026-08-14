package com.modelrag.agent.orchestrator;
import com.modelrag.agent.router.RouteDecision; import com.modelrag.qa.dto.Citation; import java.util.List;
public record AgentResult(String executionId, RouteDecision route, String status, String answer, String approvalId,
        List<String> steps, List<Citation> citations, double confidence, boolean refused, String traceId,
        List<String> degradedComponents) {
    public AgentResult(String executionId, RouteDecision route, String status, String answer, String approvalId,
            List<String> steps, List<Citation> citations, double confidence, boolean refused, String traceId) {
        this(executionId, route, status, answer, approvalId, steps, citations, confidence, refused, traceId, List.of());
    }

    public AgentResult(String executionId, RouteDecision route, String status, String answer, String approvalId, List<String> steps) {
        this(executionId, route, status, answer, approvalId, steps, List.of(), 0, false, null, List.of());
    }

    public AgentResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        citations = citations == null ? List.of() : List.copyOf(citations);
        degradedComponents = degradedComponents == null ? List.of() : List.copyOf(degradedComponents);
    }
}
