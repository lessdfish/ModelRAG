package com.modelrag.qa.controller;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.ConversationAccess;
import com.modelrag.common.sse.SseEmitterService;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.qa.trace.QaTraceView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping({"/api/v1/qa", "/api/v2/qa"})
public class QaController {
    private final QaOrchestrator qa;
    private final AccessControlService access;
    private final ConversationAccess conversations;
    private final SseEmitterService sse;
    private final Executor streamExecutor;
    private final int contextMaxTokens;

    public QaController(
            QaOrchestrator q,
            AccessControlService access,
            ConversationAccess conversations,
            SseEmitterService sse,
            @Qualifier("answerExecutor") Executor streamExecutor,
            @Value("${modelrag.qa.context-max-tokens:1600}") int contextMaxTokens) {
        qa = q;
        this.access = access;
        this.conversations = conversations;
        this.sse = sse;
        this.streamExecutor = streamExecutor;
        this.contextMaxTokens = contextMaxTokens;
    }

    @PostMapping("/ask")
    public ApiResponse<QaResult> ask(@RequestBody QaRequest request) {
        var user = access.currentUser();
        access.requireDatasetAccess(request.datasetId());
        requireConversationOwner(user.id(), request.conversationId());
        return ApiResponse.success(qa.answer(new QaRequest(request.datasetId(), request.query(), request.conversationId(), user.id(), user.roles())));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam("q") String query,
            @RequestParam("kbId") long datasetId,
            @RequestParam(required = false) Long convId,
            @RequestParam(required = false) Long conversationId) {
        var user = access.currentUser();
        access.requireDatasetAccess(datasetId);
        Long resolvedConversationId = conversationId == null ? convId : conversationId;
        requireConversationOwner(user.id(), resolvedConversationId);
        String streamKey = "qa:" + UUID.randomUUID();
        SseEmitter emitter = sse.subscribe(streamKey);
        streamExecutor.execute(() -> answerOnStream(streamKey, new QaRequest(datasetId, query, resolvedConversationId, user.id(), user.roles())));
        return emitter;
    }

    @GetMapping("/traces")
    public ApiResponse<List<QaTraceView>> traces() {
        access.requireRole("ADMIN");
        return ApiResponse.success(qa.traces().stream().map(QaTraceView::from).toList());
    }

    @GetMapping("/context-policy")
    public ApiResponse<ContextPolicyView> contextPolicy() {
        access.currentUser();
        return ApiResponse.success(new ContextPolicyView(contextMaxTokens, 3, 12, 8));
    }

    private void answerOnStream(String streamKey, QaRequest request) {
        try {
            sse.publish(streamKey, new SseEvent("THINKING", "正在理解问题", Map.of("query", request.query())));
            sse.publish(streamKey, new SseEvent("RETRIEVING", "正在检索知识库", Map.of("datasetId", request.datasetId())));
            sse.publish(streamKey, new SseEvent("GENERATING", "正在生成回答", Map.of()));
            QaResult result = qa.answer(request);
            sse.publish(streamKey, new SseEvent("DONE", "完成", Map.of(
                    "answer", result.answer(),
                    "citations", result.citations(),
                    "confidence", result.confidence(),
                    "refused", result.refused(),
                    "traceId", result.traceId() == null ? "" : result.traceId())));
        } catch (RuntimeException error) {
            sse.publish(streamKey, new SseEvent("ERROR", "问答处理失败，请稍后重试", Map.of()));
        } finally {
            sse.complete(streamKey);
        }
    }

    private void requireConversationOwner(String userId, Long conversationId) {
        conversations.requireOwner(userId, conversationId);
    }
}
