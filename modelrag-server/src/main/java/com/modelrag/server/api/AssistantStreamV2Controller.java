package com.modelrag.server.api;

import com.modelrag.agent.orchestrator.AgentOrchestrator;
import com.modelrag.agent.orchestrator.AgentExecutionRegistry;
import com.modelrag.agent.orchestrator.AgentResult;
import com.modelrag.api.AssistantAskRequest;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.sse.SseEmitterService;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/assistant")
public class AssistantStreamV2Controller {
    private final QaOrchestrator qa;
    private final AgentOrchestrator agent;
    private final AccessControlService access;
    private final SseEmitterService sse;
    private final Executor executor;
    private final AgentExecutionRegistry executions;

    public AssistantStreamV2Controller(QaOrchestrator qa, AgentOrchestrator agent, AccessControlService access,
            SseEmitterService sse, @Qualifier("answerExecutor") Executor executor,
            AgentExecutionRegistry executions) {
        this.qa = qa;
        this.agent = agent;
        this.access = access;
        this.sse = sse;
        this.executor = executor;
        this.executions = executions;
    }

    @PostMapping(value = "/streams", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AssistantAskRequest request) {
        var user = access.currentUser();
        access.requireDatasetAccess(request.datasetId());
        String executionId = UUID.randomUUID().toString();
        if (request.agent()) {
            executions.register(executionId, user.id(), request.datasetId(), request.conversationId());
        }
        String key = request.agent() ? "agent:" + executionId : "assistant:v2:" + executionId;
        SseEmitter emitter = sse.subscribe(key);
        executor.execute(() -> {
            try {
                sse.publish(key, new SseEvent("PLAN", "正在思考中", Map.of(
                        "accepted", true, "executionId", executionId)));
                var internal = new QaRequest(request.datasetId(), request.question(), request.conversationId(),
                        user.id(), user.roles());
                AtomicBoolean streamed = new AtomicBoolean();
                Object result = request.agent() ? agent.executeRegistered(internal, executionId)
                        : qa.answer(internal, token -> {
                            streamed.set(true);
                            sse.publish(key, new SseEvent("TOKEN", token, Map.of("text", token)));
                        });
                if (result instanceof com.modelrag.qa.dto.QaResult answer) {
                    if (!streamed.get()) streamAnswer(key, answer.answer());
                    publishAnswer(key, answer.citations(), answer.confidence(), answer.refused(), answer.traceId(), answer.degradedComponents());
                } else {
                    if (!(result instanceof AgentResult)) {
                        sse.publish(key, new SseEvent("ANSWER", "回答已生成", Map.of("result", result)));
                    }
                }
                if (!(result instanceof AgentResult)) sse.publish(key, new SseEvent("DONE", "完成", Map.of()));
            } catch (RuntimeException error) {
                sse.publish(key, new SseEvent("ERROR", "请求处理失败", Map.of()));
            } finally {
                sse.complete(key);
            }
        });
        return emitter;
    }

    private void streamAnswer(String key, String answer) {
        String text = answer == null ? "" : answer;
        if (!text.isBlank()) sse.publish(key, new SseEvent("TOKEN", text, Map.of("text", text)));
    }

    private void publishAnswer(String key, Object citations, double confidence, boolean refused, String traceId,
            List<String> degradedComponents) {
        sse.publish(key, new SseEvent("ANSWER", "回答已生成", Map.of(
                "citations", citations == null ? List.of() : citations,
                "confidence", confidence, "refused", refused, "traceId", traceId == null ? "" : traceId,
                "degradedComponents", degradedComponents == null ? List.of() : degradedComponents)));
    }

    @DeleteMapping("/executions/{executionId}")
    public ApiResponse<Void> cancel(@PathVariable String executionId) {
        var user = access.currentUser();
        executions.requestCancel(user, executionId);
        return ApiResponse.success(null);
    }

    @org.springframework.web.bind.annotation.GetMapping(value = "/streams/{executionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentStream(@PathVariable String executionId) {
        var user = access.currentUser();
        executions.requireSubscribe(user, executionId);
        return sse.subscribe("agent:" + executionId);
    }
}
