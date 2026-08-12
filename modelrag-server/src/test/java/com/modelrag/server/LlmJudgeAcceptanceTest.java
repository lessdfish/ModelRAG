package com.modelrag.server;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.indexing.pipeline.IndexingPipeline;
import com.modelrag.server.eval.EvalController;
import com.modelrag.server.eval.EvalItem;
import com.modelrag.server.eval.EvalReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"modelrag.security.default-admin-enabled=true", "modelrag.eval.llm-judge-enabled=true", "modelrag.ollama.enabled=false"})
class LlmJudgeAcceptanceTest {
    @Autowired KnowledgeStore store;
    @Autowired IndexingPipeline indexing;
    @Autowired EvalController eval;

    @Test
    void llmJudgeFallbackIsRecordedWhenOnlyMockModelIsAvailable() {
        long datasetId = store.createDataset("LLM Judge 回退库", "", 512, 64).id();
        Document doc = store.addDocument(datasetId, "judge.md", "MD", "judge-fallback",
                "LLM Judge 回退条款：如果评测模型不可用，报告必须写明回退到启发式评估。");
        indexing.index(doc.id());
        long expected = store.chunks(datasetId).get(0).id();

        EvalReport report = eval.run(datasetId, List.of(new EvalItem("评测模型不可用时报告写明什么", List.of(expected)))).data();

        assertEquals("LLM", report.parameters().get("judgeRequestedMode"));
        assertEquals(1, ((Number) report.parameters().get("judgeFallbacks")).intValue());
        assertTrue(String.valueOf(report.parameters().get("judgeModeCounts")).contains("HEURISTIC_FALLBACK"));
        assertEquals("HEURISTIC_FALLBACK", report.caseResults().get(0).get("judgeMode"));
    }
}
