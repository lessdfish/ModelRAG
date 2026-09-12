package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.evidence.EvidenceSet;
import com.modelrag.qa.evidence.EvidenceSufficiency;
import com.modelrag.qa.orchestrator.QaExecutionSnapshot;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.qa.orchestrator.QaV2ApplicationService;
import com.modelrag.qa.orchestrator.QaV2ExecutionSnapshot;
import com.modelrag.common.observability.RetrievalMetrics;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import com.modelrag.server.eval.EvalItem;
import com.modelrag.server.eval.EvaluationObservation;
import com.modelrag.server.eval.V1EvaluationAdapter;
import com.modelrag.server.eval.V2EvaluationAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G111EvaluationExecutionTest {
    @Test
    void v1EvaluationUsesOnlyTheQaExecutionRetrieval() {
        SearchFacade search = mock(SearchFacade.class);
        QaOrchestrator qa = mock(QaOrchestrator.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        SearchStages stages = new SearchStages("q", List.of("q"), "q", List.of(), List.of(), List.of(),
                List.of(), false, List.of(), List.of(), Map.of("total", 1L));
        when(qa.executeV1ForEvaluation(any(), eq(5), eq(.72))).thenReturn(new QaExecutionSnapshot(
                new QaResult("answer", List.of(), 1, false, "trace", List.of()), stages, List.of(), 1));
        when(chunks.findActiveByIds(eq(7L), any())).thenReturn(List.of());

        EvaluationObservation observation = new V1EvaluationAdapter(search, qa, chunks)
                .run(7, item(), 5, .72);

        verify(qa).executeV1ForEvaluation(any(), eq(5), eq(.72));
        verify(search, never()).inspect(any());
        assertFalse(observation.error());
    }

    @Test
    void v2EvaluationUsesOneQaExecutionAndTheProvidedDatasetThreshold() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        QaV2ApplicationService qa = mock(QaV2ApplicationService.class);
        RetrievalV2Stages stages = new RetrievalV2Stages("q", List.of("q"), "q", List.of(), List.of(),
                List.of(), List.of(), List.of(), false, List.of(), Map.of("total", 1L));
        EvidenceSet evidence = new EvidenceSet("trace", "q", List.of(),
                new EvidenceSufficiency(false, 0, "missing", 0), List.of(), 1, 0);
        when(qa.executeForEvaluation(any(), eq(5), eq(.72))).thenReturn(new QaV2ExecutionSnapshot(
                new QaResult("insufficient", List.of(), 0, true, "trace", List.of()), stages, evidence, 1));

        EvaluationObservation observation = new V2EvaluationAdapter(retrieval, qa, "qwen3-v1")
                .run(7, item(), 5, .72);

        verify(qa).executeForEvaluation(any(), eq(5), eq(.72));
        verify(retrieval, never()).inspect(any());
        assertTrue(observation.refused());
        assertFalse(observation.error());
    }

    @Test
    void technicalFailureIsErrorAndNeverBusinessRefusal() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        QaV2ApplicationService qa = mock(QaV2ApplicationService.class);
        when(qa.executeForEvaluation(any(), eq(5), eq(.72)))
                .thenThrow(new IllegalStateException("database unavailable"));

        EvaluationObservation observation = new V2EvaluationAdapter(retrieval, qa, "qwen3-v1")
                .run(7, item(), 5, .72);

        assertTrue(observation.error());
        assertFalse(observation.refused());
    }

    @Test
    void qaLayerDoesNotOwnRetrievalRequestMetrics() {
        assertTrue(java.util.Arrays.stream(QaOrchestrator.class.getDeclaredFields())
                .noneMatch(field -> field.getType().equals(RetrievalMetrics.class)));
        assertTrue(java.util.Arrays.stream(QaV2ApplicationService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().equals(RetrievalMetrics.class)));
    }

    private EvalItem item() {
        return new EvalItem("q", List.of(), "", false, "POINT_FACT");
    }
}
