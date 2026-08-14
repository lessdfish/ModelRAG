package com.modelrag.agent.controller;

import com.modelrag.agent.auto.AutoQaRequest;
import com.modelrag.agent.auto.AutoQaResult;
import com.modelrag.agent.auto.AutoQaService;
import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.RequestUser;
import com.modelrag.common.sse.SseEmitterService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/qa")
public class AutoQaController {
    private final AutoQaService auto;
    private final AccessControlService access;
    private final ConversationMemory memory;
    private final SseEmitterService sse;
    private final Executor streamExecutor;

    public AutoQaController(
            AutoQaService auto,
            AccessControlService access,
            ConversationMemory memory,
            SseEmitterService sse,
            @Qualifier("answerExecutor") Executor streamExecutor) {
        this.auto = auto;
        this.access = access;
        this.memory = memory;
        this.sse = sse;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping("/auto")
    public ApiResponse<AutoQaResult> answer(@RequestBody AutoQaRequest request) {
        RequestUser user = access.currentUser();
        Set<Long> allowed = user.roles().contains("ADMIN") ? Set.of() : user.datasetIds();
        access.requireAnyDatasetAccess(allowed);
        memory.requireOwner(user.id(), request.conversationId());
        return ApiResponse.success(auto.answer(request, allowed, user.id(), user.roles()));
    }

    @GetMapping(value = "/auto/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("q") String query, @RequestParam(required = false) Long convId) {
        RequestUser user = access.currentUser();
        Set<Long> allowed = user.roles().contains("ADMIN") ? Set.of() : user.datasetIds();
        access.requireAnyDatasetAccess(allowed);
        memory.requireOwner(user.id(), convId);
        String streamKey = "qa:auto:" + UUID.randomUUID();
        SseEmitter emitter = sse.subscribe(streamKey);
        streamExecutor.execute(() -> answerOnStream(streamKey, new AutoQaRequest(query, convId), allowed, user.id(), user.roles()));
        return emitter;
    }

    private void answerOnStream(String streamKey, AutoQaRequest request, Set<Long> allowed, String userId, Set<String> userRoles) {
        try {
            sse.publish(streamKey, new SseEvent("THINKING", "正在理解问题", Map.of("query", request.query())));
            sse.publish(streamKey, new SseEvent("ROUTING", "正在自动选择知识库和执行模式", Map.of()));
            AutoQaResult result = auto.answer(request, allowed, userId, userRoles);
            sse.publish(streamKey, new SseEvent("DONE", "自动问答完成", Map.of("result", result)));
        } catch (RuntimeException error) {
            sse.publish(streamKey, new SseEvent("ERROR", "自动问答处理失败，请稍后重试", Map.of()));
        } finally {
            sse.complete(streamKey);
        }
    }
}
