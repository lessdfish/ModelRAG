package com.modelrag.toolgateway.policy;

import com.modelrag.toolgateway.catalog.ToolDescriptor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deterministic risk, approval, retry, and recovery classification. */
@Component
@Profile("!test")
public class ToolRiskPolicy {
    public boolean requiresApproval(ToolDescriptor tool) {
        return tool != null && ("HIGH".equalsIgnoreCase(tool.riskLevel())
                || "EXTERNAL_SIDE_EFFECT".equalsIgnoreCase(tool.riskLevel()));
    }

    public boolean isSideEffect(ToolDescriptor tool) {
        return tool != null && !"LOW".equalsIgnoreCase(tool.riskLevel());
    }

    public boolean safeToRetry(ToolDescriptor tool) {
        return tool != null && (tool.idempotent()
                || ("LOW".equalsIgnoreCase(tool.riskLevel()) && !tool.http()));
    }

    public boolean safeToReplayAfterCrash(ToolDescriptor tool) {
        return safeToRetry(tool);
    }
}
