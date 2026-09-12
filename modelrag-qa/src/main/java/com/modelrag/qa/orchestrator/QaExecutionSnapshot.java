package com.modelrag.qa.orchestrator;

import com.modelrag.qa.dto.QaResult;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import java.util.List;

/** One V1 QA execution and the exact retrieval result used to produce its answer. */
public record QaExecutionSnapshot(QaResult result, SearchStages retrievalStages,
        List<ScoredChunk> selectedEvidence, long latencyMs) {
    public QaExecutionSnapshot {
        selectedEvidence = selectedEvidence == null ? List.of() : List.copyOf(selectedEvidence);
        latencyMs = Math.max(0, latencyMs);
    }
}
