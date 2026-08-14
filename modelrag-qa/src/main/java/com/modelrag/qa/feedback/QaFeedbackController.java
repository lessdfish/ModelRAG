package com.modelrag.qa.feedback;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.qa.trace.QaTraceView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QaFeedbackController {
    private final QaFeedbackService feedback;
    private final QaOrchestrator qa;
    private final AccessControlService access;

    public QaFeedbackController(QaFeedbackService feedback, QaOrchestrator qa, AccessControlService access) {
        this.feedback = feedback;
        this.qa = qa;
        this.access = access;
    }

    @PostMapping({"/api/v1/qa/feedback", "/api/v2/qa/feedback"})
    public ApiResponse<FeedbackView> submit(@Valid @RequestBody FeedbackRequest body) {
        var user = access.currentUser();
        String traceId = body.traceId();
        QaTraceView replay = QaTraceView.from(qa.replayTrace(traceId));
        if (!replay.found() || replay.datasetId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "反馈对应的 Trace 不存在");
        }
        access.requireDatasetAccess(replay.datasetId());
        return ApiResponse.success(feedback.submit(replay.datasetId(), traceId, user.id(),
                body.rating(), body.comment()));
    }

    @GetMapping({"/api/v1/admin/feedbacks", "/api/v2/admin/feedbacks"})
    public ApiResponse<List<FeedbackView>> list() {
        access.requireRole("ADMIN");
        return ApiResponse.success(feedback.list());
    }
}
