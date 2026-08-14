package com.modelrag.agent.controller;

import com.modelrag.agent.memory.LongTermMemoryService;
import com.modelrag.api.MemoryRememberRequest;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/memories")
public class MemoryV2Controller {
    private final LongTermMemoryService memory;
    private final AccessControlService access;

    public MemoryV2Controller(LongTermMemoryService memory, AccessControlService access) {
        this.memory = memory;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<LongTermMemoryService.Memory>> list(
            @RequestParam(required = false) Long datasetId,
            @RequestParam(defaultValue = "") String query) {
        var user = access.currentUser();
        if (datasetId != null) access.requireDatasetAccess(datasetId);
        return ApiResponse.success(memory.retrieveRelevant(user.id(), datasetId, query, 20, true));
    }

    @PostMapping("/remember")
    public ApiResponse<LongTermMemoryService.Memory> remember(@Valid @RequestBody MemoryRememberRequest request) {
        var user = access.currentUser();
        if (request.datasetId() != null) access.requireDatasetAccess(request.datasetId());
        return ApiResponse.success(memory.upsert(toMemory(user.id(), null, request, true)));
    }

    @PostMapping("/{memoryId}/confirm")
    public ApiResponse<LongTermMemoryService.Memory> confirm(@PathVariable String memoryId) {
        var user = access.currentUser();
        return ApiResponse.success(memory.confirm(user.id(), memoryId));
    }

    @PostMapping("/{memoryId}/reject")
    public ApiResponse<LongTermMemoryService.Memory> reject(@PathVariable String memoryId) {
        var user = access.currentUser();
        return ApiResponse.success(memory.reject(user.id(), memoryId));
    }

    @PutMapping("/{memoryId}")
    public ApiResponse<LongTermMemoryService.Memory> update(@PathVariable String memoryId,
            @Valid @RequestBody MemoryRememberRequest request) {
        var user = access.currentUser();
        if (request.datasetId() != null) access.requireDatasetAccess(request.datasetId());
        return ApiResponse.success(memory.upsert(toMemory(user.id(), memoryId, request, request.confirmed())));
    }

    @DeleteMapping("/{memoryId}")
    public ApiResponse<Void> delete(@PathVariable String memoryId) {
        memory.delete(access.currentUser().id(), memoryId);
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> clear(@RequestParam(required = false) Long datasetId) {
        var user = access.currentUser();
        if (datasetId != null) access.requireDatasetAccess(datasetId);
        memory.clear(user.id(), datasetId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{memoryId}/pause")
    public ApiResponse<LongTermMemoryService.Memory> pause(@PathVariable String memoryId) {
        var user = access.currentUser();
        return ApiResponse.success(memory.pause(user.id(), memoryId));
    }

    @PostMapping("/{memoryId}/resume")
    public ApiResponse<LongTermMemoryService.Memory> resume(@PathVariable String memoryId) {
        var user = access.currentUser();
        return ApiResponse.success(memory.resume(user.id(), memoryId));
    }

    @GetMapping("/export")
    public ApiResponse<List<LongTermMemoryService.Memory>> export(@RequestParam(required = false) Long datasetId) {
        var user = access.currentUser();
        if (datasetId != null) access.requireDatasetAccess(datasetId);
        return ApiResponse.success(memory.list(user.id(), datasetId, true, 100));
    }

    private LongTermMemoryService.Memory toMemory(String userId, String id, MemoryRememberRequest request,
            boolean confirmed) {
        return new LongTermMemoryService.Memory(id, userId, request.datasetId(),
                request.datasetId() == null ? "USER_GLOBAL" : "DATASET",
                request.type() == null ? "PREFERENCE" : request.type(), request.memoryKey(), request.content(),
                confirmed ? "ACTIVE" : "PENDING", confirmed ? .9 : .5, confirmed ? .9 : .5, null);
    }
}
