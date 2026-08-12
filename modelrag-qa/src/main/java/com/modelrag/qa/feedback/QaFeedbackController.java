package com.modelrag.qa.feedback;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @PostMapping("/api/v1/qa/feedback")
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        var user = access.currentUser();
        String traceId = Objects.toString(body.get("traceId"), "");
        Map<String, Object> replay = qa.replayTrace(traceId);
        if (!Boolean.TRUE.equals(replay.get("found")) || !(replay.get("datasetId") instanceof Number datasetId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "反馈对应的 Trace 不存在");
        }
        access.requireDatasetAccess(datasetId.longValue());
        return ApiResponse.success(feedback.submit(datasetId.longValue(), traceId, user.id(),
                Objects.toString(body.get("rating"), ""), Objects.toString(body.get("comment"), "")));
    }

    @GetMapping("/api/v1/admin/feedbacks")
    public ApiResponse<List<Map<String, Object>>> list() {
        access.requireRole("ADMIN");
        return ApiResponse.success(feedback.list());
    }
}
