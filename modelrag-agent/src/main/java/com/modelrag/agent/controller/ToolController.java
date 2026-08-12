package com.modelrag.agent.controller;

import com.modelrag.agent.tool.ToolDefinition;
import com.modelrag.agent.tool.ToolRegistry;
import com.modelrag.agent.trace.ToolCallTrace;
import com.modelrag.agent.trace.ToolCallTracer;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.RequestUser;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {
    private final ToolRegistry tools;
    private final ToolCallTracer traces;
    private final AccessControlService access;

    public ToolController(ToolRegistry tools, ToolCallTracer traces, AccessControlService access) {
        this.tools = tools;
        this.traces = traces;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<ToolDefinition>> list() {
        RequestUser user = access.currentUser();
        return ApiResponse.success(tools.listEnabled().stream()
                .filter(tool -> visibleTo(tool, user))
                .map(this::redact)
                .toList());
    }

    @GetMapping("/all")
    public ApiResponse<List<ToolDefinition>> all() {
        access.requireRole("ADMIN");
        return ApiResponse.success(tools.list().stream().map(this::redact).toList());
    }

    @PostMapping
    public ApiResponse<ToolDefinition> register(@RequestBody ToolDefinition tool) {
        access.requireRole("ADMIN");
        return ApiResponse.success(redact(tools.register(tool)));
    }

    @PostMapping("/{name}/enabled")
    public ApiResponse<ToolDefinition> enabled(@PathVariable String name, @RequestParam boolean value) {
        access.requireRole("ADMIN");
        return ApiResponse.success(redact(tools.setEnabled(name, value)));
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

    private boolean visibleTo(ToolDefinition tool, RequestUser user) {
        boolean roleAllowed = tool.allowedRoles() == null || tool.allowedRoles().isEmpty()
                || tool.allowedRoles().stream().anyMatch(user::hasRole);
        boolean datasetAllowed = tool.allowedDatasetIds() == null || tool.allowedDatasetIds().isEmpty()
                || user.roles().contains("ADMIN")
                || tool.allowedDatasetIds().stream().anyMatch(user.datasetIds()::contains);
        return roleAllowed && datasetAllowed;
    }

    private ToolDefinition redact(ToolDefinition tool) {
        return new ToolDefinition(tool.name(), tool.description(), tool.riskLevel(), tool.enabled(), tool.type(),
                tool.endpoint(), tool.authHeaderName(), tool.authHeaderValue() == null ? null : "******",
                tool.jsonSchema(), tool.allowedRoles(), tool.allowedDatasetIds());
    }
}
