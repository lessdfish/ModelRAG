package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.modelrag.indexing.pipeline.IndexingPipeline;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.service.DocumentService;
import com.modelrag.knowledge.service.DocumentDeletionService;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.knowledge.service.ObjectStorageService;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "modelrag.ollama.enabled=false",
        "modelrag.reranker.enabled=false"
})
@ActiveProfiles("test")
class M0AcceptanceTest {
    @Autowired KnowledgeStore store;
    @Autowired IndexingPipeline indexing;
    @Autowired QaOrchestrator qa;
    @Autowired DocumentService documents;
    @Autowired DocumentDeletionService deletion;
    @Autowired ObjectStorageService storage;
    @Autowired IndexOutbox outbox;

    @Test
    void uploadUsesObjectStorageAndClosesTheIndexToCitationLoop() throws Exception {
        Dataset dataset = store.createDataset("M0 知识库", "", 512, 64);
        MockMultipartFile file = new MockMultipartFile(
                "file", "leave.md", "text/markdown",
                "# 年假制度\n\n员工每年有五天年假，申请需要直属主管审批。".getBytes(StandardCharsets.UTF_8));

        Document document = documents.upload(dataset.id(), file);

        assertNull(document.content());
        assertFalse(document.sourceObjectKey().isBlank());
        assertFalse(document.artifactObjectKey().isBlank());
        assertNotNull(storage.open(document.artifactObjectKey()));

        indexing.index(document.id());

        assertTrue(Set.of("SEARCH_SYNCING", "READY").contains(store.document(document.id()).status()));
        assertFalse(store.chunks(dataset.id()).isEmpty());
        QaResult result = qa.answer(new QaRequest(dataset.id(), "年假申请需要谁审批", null));
        assertFalse(result.refused());
        assertFalse(result.citations().isEmpty());
        assertEquals(document.id(), result.citations().get(0).documentId());
        assertEquals("leave.md", result.citations().get(0).documentName());
        assertTrue(result.citations().get(0).indexVersion() > 0);
    }

    @Test
    void everyQuestionGetsAnIndependentTraceWithoutAnOnlineAnswerCache() {
        Dataset dataset = store.createDataset("M0 Trace 知识库", "", 512, 64);
        Document document = store.addDocument(dataset.id(), "trace.md", "MD", "m0-trace",
                "审计记录必须保留问题、答案和证据引用。 ");
        indexing.index(document.id());

        QaRequest request = new QaRequest(dataset.id(), "审计记录保留什么", null);
        QaResult first = qa.answer(request);
        QaResult second = qa.answer(request);

        assertFalse(first.refused());
        assertFalse(second.refused());
        assertNotEquals(first.traceId(), second.traceId());
        assertTrue(String.valueOf(qa.replayTrace(second.traceId()).get("abVariants")).isBlank()
                || "[]".equals(String.valueOf(qa.replayTrace(second.traceId()).get("abVariants"))));
    }

    @Test
    void emptyKnowledgeBaseRefusesInsteadOfInventingEvidence() {
        Dataset dataset = store.createDataset("M0 空知识库", "", 512, 64);
        QaResult result = qa.answer(new QaRequest(dataset.id(), "不存在的制度是什么", null));
        assertTrue(result.refused());
        assertTrue(result.citations().isEmpty());
        assertEquals("当前知识库没有足够证据回答该问题。", result.answer());
    }

    @Test
    void decisionEvidenceAnswersWithCitationWhileUnrelatedQuestionRefusesCleanly() {
        Dataset dataset = store.createDataset("M0 拒答边界", "", 512, 64);
        Document document = store.addDocument(dataset.id(), "leave-faq.md", "MD", "m0-refusal-boundary",
                "问：谁审批年假？答：直属主管审批，连续超过 3 个工作日时部门负责人同步确认。");
        indexing.index(document.id());

        QaResult decision = qa.answer(new QaRequest(dataset.id(), "连续请 4 天还需要谁确认？", null));
        assertFalse(decision.refused());
        assertFalse(decision.citations().isEmpty());
        assertTrue(decision.answer().contains("部门负责人"));

        QaResult unrelated = qa.answer(new QaRequest(dataset.id(), "公司的新加坡办公室地址是什么？", null));
        assertTrue(unrelated.refused());
        assertTrue(unrelated.citations().isEmpty());
        assertEquals("当前知识库没有足够证据回答该问题。", unrelated.answer());
    }

    @Test
    void focusedPolicySentenceBeatsWeaklyRelatedFaq() {
        Dataset dataset = store.createDataset("M0 证据优先级", "", 512, 64);
        Document document = store.addDocument(dataset.id(), "leave-policy.md", "MD", "m0-evidence-priority",
                "员工每自然年度享有 5 个工作日带薪年假。年假应至少提前 3 个工作日提交。\n\n"
                        + "问：年假有几天？答：正式员工每自然年度有 5 个工作日带薪年假。");
        indexing.index(document.id());

        QaResult result = qa.answer(new QaRequest(dataset.id(), "年假应提前多久提交？", null));

        assertFalse(result.refused());
        assertTrue(result.answer().contains("提前 3 个工作日"));
    }

    @Test
    void faqAnswerMustMatchTheQuestionIntentInsteadOfARelevantRestriction() {
        Dataset dataset = store.createDataset("M0 回答意图", "", 512, 64);
        Document document = store.addDocument(dataset.id(), "incident.md", "MD", "m0-answer-intent",
                "任何员工发现可疑事件后，应立即停止可能扩大影响的操作，并保留证据。"
                        + "任何员工不得未经授权向客户或媒体披露安全事件细节。"
                        + "披露信息只能由法务、合规和指定发言人执行。\n\n"
                        + "问：发现账号可能泄露时第一步是什么？答：停止可疑操作并立即报告，同时保留证据。"
                        + "问：谁可以对外说明事件？答：法务、合规和指定发言人。");
        indexing.index(document.id());

        QaResult firstStep = qa.answer(new QaRequest(dataset.id(), "发生疑似数据泄露时第一发现人首先应如何处理？", null));
        QaResult disclosure = qa.answer(new QaRequest(dataset.id(), "谁可以向客户或媒体披露安全事件信息？", null));

        assertTrue(firstStep.answer().contains("停止"));
        assertTrue(disclosure.answer().contains("法务"), disclosure.answer());
    }

    @Test
    void requestedTimeRangeBeatsAWeaklyRelatedFaq() {
        Dataset dataset = store.createDataset("M0 时段证据", "", 512, 64);
        Document document = store.addDocument(dataset.id(), "remote.md", "MD", "m0-time-range",
                "远程期间，员工应在核心协作时段 10:00 至 16:00 保持可联系状态。\n\n"
                        + "问：远程办公是否自动批准？答：不是；需要根据岗位、交付、安全条件和团队覆盖情况审批。");
        indexing.index(document.id());

        QaResult result = qa.answer(new QaRequest(dataset.id(), "远程办公的核心协作时段是什么？", null));

        assertTrue(result.answer().contains("10:00 至 16:00"), result.answer());
    }

    @Test
    void rejectsMimeSpoofedUploadBeforeStorage() {
        Dataset dataset = store.createDataset("M0 上传安全", "", 512, 64);
        MockMultipartFile spoofedPdf = new MockMultipartFile(
                "file", "spoof.pdf", "application/pdf", "这不是 PDF".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> documents.upload(dataset.id(), spoofedPdf));
    }

    @Test
    void documentDeletionSoftDeletesBeforeDeferredExternalCleanup() {
        Dataset dataset = store.createDataset("M0 删除一致性", "", 512, 64);
        Document document = store.addDocument(dataset.id(), "delete.md", "MD", "delete-consistency",
                "删除必须先阻断检索，再异步清理外部副本。", "source/delete", "artifact/delete", "normalized");
        indexing.index(document.id());

        deletion.deleteDocument(dataset.id(), document.id());

        assertThrows(RuntimeException.class, () -> store.document(document.id()));
        assertTrue(outbox.due().stream().anyMatch(event -> "DELETE_DOCUMENT".equals(event.eventType())
                && event.documentId() == document.id()
                && event.payload().contains("source/delete")
                && event.payload().contains("artifact/delete")));
        assertTrue(qa.answer(new QaRequest(dataset.id(), "删除必须如何清理", null)).refused());
    }

    @Test
    void parentChunkGroupsStayNearTheEighteenHundredTokenBudget() {
        Dataset dataset = store.createDataset("M0 父块预算", "", 600, 0);
        Document document = store.addDocument(dataset.id(), "parent.md", "MD", "parent-budget",
                "父块预算内容。".repeat(900));

        indexing.index(document.id());

        var chunks = store.chunks(dataset.id());
        assertTrue(chunks.size() >= 4);
        assertTrue(chunks.stream().map(com.modelrag.knowledge.model.Chunk::parentChunkId).distinct().count() >= 2);
        assertTrue(chunks.stream().collect(java.util.stream.Collectors.groupingBy(
                        com.modelrag.knowledge.model.Chunk::parentChunkId))
                .values().stream().allMatch(group -> group.size() <= 3));
    }
}
