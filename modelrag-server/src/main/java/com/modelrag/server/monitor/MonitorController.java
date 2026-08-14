package com.modelrag.server.monitor;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.server.model.ModelCandidateRequest;
import com.modelrag.server.model.ModelClient;
import com.modelrag.server.model.ModelHealthStore;
import com.modelrag.server.model.ModelHealthView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/monitor", "/api/v2/monitor"})
public class MonitorController {
    private final TokenUsageTracker tokens;
    private final AccessControlService access;
    private final ModelHealthStore models;
    private final List<ModelClient> clients;

    public MonitorController(TokenUsageTracker tokens, AccessControlService access,
            ModelHealthStore models, List<ModelClient> clients) {
        this.tokens = tokens;
        this.access = access;
        this.models = models;
        this.clients = clients;
    }

    @GetMapping("/overview")
    public ApiResponse<TokenUsageTracker.Usage> overview(@RequestParam long datasetId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(tokens.usage(datasetId));
    }

    @GetMapping("/model-health")
    public ApiResponse<List<ModelHealthView>> modelHealth() {
        access.requireRole("ADMIN");
        return ApiResponse.success(models.snapshot(clients));
    }

    @PutMapping("/model-candidates")
    public ApiResponse<ModelHealthView> saveModelCandidate(@Valid @RequestBody ModelCandidateRequest request) {
        access.requireRole("ADMIN");
        return ApiResponse.success(models.saveCandidate(request.modelType(), request.modelName(), request.provider(),
                request.safePriority(), request.safeEnabled(), request.safeCanaryPercent()));
    }
}
