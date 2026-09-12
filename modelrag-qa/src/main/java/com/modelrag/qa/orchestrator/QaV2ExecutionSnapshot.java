package com.modelrag.qa.orchestrator;

import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.evidence.EvidenceSet;
import com.modelrag.search.dto.RetrievalV2Stages;

/** One V2 QA execution and the exact retrieval/evidence used to produce its answer. */
public record QaV2ExecutionSnapshot(QaResult result, RetrievalV2Stages retrievalStages,
        EvidenceSet evidenceSet, long latencyMs) {
    public QaV2ExecutionSnapshot {
        latencyMs = Math.max(0, latencyMs);
    }
}
