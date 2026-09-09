package com.modelrag.toolgateway.policy;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.execution.ToolInvocation;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deterministic role and dataset authorization for every gateway invocation. */
@Component
@Profile("!test")
public class ToolAccessPolicy {
    public boolean allowed(ToolDescriptor tool, String userId, Set<String> roles, long datasetId) {
        if (tool == null || !tool.enabled()) return false;
        Set<String> callerRoles = roles == null ? Set.of() : roles;
        boolean roleAllowed = tool.allowedRoles().isEmpty()
                || tool.allowedRoles().stream().anyMatch(role -> callerRoles.stream()
                        .anyMatch(caller -> role.equalsIgnoreCase(caller)));
        boolean datasetAllowed = tool.allowedDatasetIds().isEmpty()
                || tool.allowedDatasetIds().contains(datasetId);
        return userId != null && !userId.isBlank() && roleAllowed && datasetAllowed;
    }

    public boolean allowed(ToolDescriptor tool, ToolInvocation invocation) {
        return invocation != null && allowed(tool, invocation.userId(), invocation.userRoles(), invocation.datasetId());
    }

    public void requireAllowed(ToolDescriptor tool, ToolInvocation invocation) {
        if (!allowed(tool, invocation)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户无权调用该工具");
        }
    }
}
