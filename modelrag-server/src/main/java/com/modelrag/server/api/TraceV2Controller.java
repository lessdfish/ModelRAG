package com.modelrag.server.api;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.qa.trace.QaTraceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/traces")
public class TraceV2Controller {
    private final QaOrchestrator qa;
    private final AccessControlService access;

    public TraceV2Controller(QaOrchestrator qa, AccessControlService access) {
        this.qa = qa;
        this.access = access;
    }

    @GetMapping("/{traceId}/replay")
    public ApiResponse<QaTraceView> replay(@PathVariable String traceId) {
        access.requireRole("ADMIN");
        return ApiResponse.success(QaTraceView.from(qa.replayTrace(traceId)));
    }
}
