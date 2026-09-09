package com.modelrag.agent.controller;

import com.modelrag.toolgateway.trace.ToolCallTrace;
import com.modelrag.toolgateway.trace.ToolCallTracer;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.RequestUser;
import com.modelrag.toolgateway.catalog.ToolCatalog;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.catalog.ToolRegistrationCommand;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {
    private final ToolCatalog tools;
    private final ToolCallTracer traces;
    private final AccessControlService access;

    public ToolController(ToolCatalog tools, ToolCallTracer traces, AccessControlService access) {
        this.tools = tools;
        this.traces = traces;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<ToolDescriptor>> list() {
        RequestUser user = access.currentUser();
        return ApiResponse.success(tools.listEnabled().stream()
                .filter(tool -> visibleTo(tool, user))
                .toList());
    }

    @GetMapping("/all")
    public ApiResponse<List<ToolDescriptor>> all() {
        access.requireRole("ADMIN");
        return ApiResponse.success(tools.list());
    }

    @PostMapping
    public ApiResponse<ToolDescriptor> register(@RequestBody ToolRegistrationCommand tool) {
        access.requireRole("ADMIN");
        return ApiResponse.success(tools.register(tool));
    }

    @PostMapping("/{name}/enabled")
    public ApiResponse<ToolDescriptor> enabled(@PathVariable String name, @RequestParam boolean value) {
        access.requireRole("ADMIN");
        return ApiResponse.success(tools.setEnabled(name, value));
    }

    @DeleteMapping("/{name}")
    public ApiResponse<Void> remove(@PathVariable String name) {
        access.requireRole("ADMIN");
        tools.remove(name);
        return ApiResponse.success(null);
    }

    @GetMapping("/traces")
    public ApiResponse<List<ToolCallTrace>> traces() {
        access.requireRole("ADMIN");
        return ApiResponse.success(traces.list());
    }

    private boolean visibleTo(ToolDescriptor tool, RequestUser user) {
        boolean roleAllowed = tool.allowedRoles() == null || tool.allowedRoles().isEmpty()
                || tool.allowedRoles().stream().anyMatch(user::hasRole);
        boolean datasetAllowed = tool.allowedDatasetIds() == null || tool.allowedDatasetIds().isEmpty()
                || user.roles().contains("ADMIN")
                || tool.allowedDatasetIds().stream().anyMatch(user.datasetIds()::contains);
        return roleAllowed && datasetAllowed;
    }

}
