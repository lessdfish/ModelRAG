package com.modelrag.server.ab;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.qa.ab.OnlineExperimentService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {
    private final OnlineExperimentService experiments;
    private final AccessControlService access;

    public ExperimentController(OnlineExperimentService experiments, AccessControlService access) {
        this.experiments = experiments;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<OnlineExperimentService.Experiment>> list(@RequestParam long datasetId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(experiments.list(datasetId));
    }

    @PostMapping
    public ApiResponse<OnlineExperimentService.Experiment> save(@RequestBody Map<String, Object> body) {
        access.requireRole("ADMIN");
        return ApiResponse.success(experiments.save(body));
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<OnlineExperimentService.Experiment> enabled(@PathVariable String id, @RequestParam boolean value) {
        access.requireRole("ADMIN");
        return ApiResponse.success(experiments.setEnabled(id, value));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        access.requireRole("ADMIN");
        experiments.delete(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/report")
    public ApiResponse<List<Map<String, Object>>> report(@RequestParam long datasetId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(experiments.report(datasetId));
    }
}
