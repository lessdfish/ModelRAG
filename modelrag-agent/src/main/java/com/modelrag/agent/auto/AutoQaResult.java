package com.modelrag.agent.auto;

import com.modelrag.agent.router.RouteDecision;
import com.modelrag.qa.dto.Citation;
import java.util.List;

public record AutoQaResult(
        long datasetId,
        String datasetName,
        RouteDecision route,
        String status,
        String answer,
        List<Citation> citations,
        double confidence,
        boolean refused,
        String traceId,
        String executionId,
        String approvalId,
        List<String> steps) {}
