package com.modelrag.server.api;

import com.modelrag.agent.auto.AutoQaRequest;
import com.modelrag.agent.auto.AutoQaResult;
import com.modelrag.agent.auto.AutoQaService;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.RequestUser;
import com.modelrag.common.sse.SseEmitterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/assistant/auto")
public class AssistantAutoV2Controller {
    private static final Logger LOG = LoggerFactory.getLogger(AssistantAutoV2Controller.class);
    private final AutoQaService auto;
    private final AccessControlService access;
    private final SseEmitterService sse;
    private final Executor executor;

    public AssistantAutoV2Controller(AutoQaService auto, AccessControlService access, SseEmitterService sse,
            @Qualifier("answerExecutor") Executor executor) {
        this.auto = auto;
        this.access = access;
        this.sse = sse;
        this.executor = executor;
    }

    @PostMapping
    public ApiResponse<AutoQaResult> ask(@Valid @RequestBody AutoRequest request) {
        RequestUser user = access.currentUser();
        Set<Long> allowed = user.hasRole("ADMIN") ? Set.of() : user.datasetIds();
        access.requireAnyDatasetAccess(allowed);
        return ApiResponse.success(auto.answer(new AutoQaRequest(request.question(), request.conversationId()),
                allowed, user.id(), user.roles()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AutoRequest request) {
        RequestUser user = access.currentUser();
        Set<Long> allowed = user.hasRole("ADMIN") ? Set.of() : user.datasetIds();
        access.requireAnyDatasetAccess(allowed);
        String key = "assistant:v2:auto:" + UUID.randomUUID();
        String executionId = UUID.randomUUID().toString();
        SseEmitter emitter = sse.subscribe(key);
        executor.execute(() -> {
            try {
                sse.publish(key, new SseEvent("PLAN", "正在思考中", Map.of()));
                AtomicBoolean streamed = new AtomicBoolean();
                AutoQaResult result = auto.answer(new AutoQaRequest(request.question(), request.conversationId()),
                        allowed, user.id(), user.roles(), token -> {
                            streamed.set(true);
                            sse.publish(key, new SseEvent("TOKEN", token, Map.of("text", token)));
                        }, executionId, id -> sse.publish(key, new SseEvent("AGENT_STARTED",
                                "Agent 执行已启动", Map.of("executionId", id))));
                if (!streamed.get()) streamAnswer(key, result.answer());
                sse.publish(key, new SseEvent("DONE", "自动问答完成", Map.of("result", result)));
            } catch (RuntimeException error) {
                LOG.error("Automatic QA stream failed: executionId={}, userId={}", executionId, user.id(), error);
                sse.publish(key, new SseEvent("ERROR", "自动问答处理失败", Map.of()));
            } finally {
                sse.complete(key);
            }
        });
        return emitter;
    }

    private void streamAnswer(String key, String answer) {
        String text = answer == null ? "" : answer;
        if (!text.isBlank()) sse.publish(key, new SseEvent("TOKEN", text, Map.of("text", text, "streamed", false)));
    }

    public record AutoRequest(@NotBlank @Size(max = 20_000) String question, Long conversationId) {}
}
