package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.knowledge.service.DocumentNavigationService;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceExpansionService;
import com.modelrag.qa.evidence.EvidenceOrigin;
import com.modelrag.qa.evidence.EvidenceRetrievalService;
import com.modelrag.qa.evidence.EvidenceSelector;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G6EvidenceNavigationTest {
    @Test
    void candidateBecomesPrimaryOnlyAfterActiveUnitAndNodeValidation() {
        RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
        DocumentStructureRepository structures = mock(DocumentStructureRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        RetrievalUnit unit = unit(101, 55, 31);
        DocumentNode node = node(55, null, NodeType.PARAGRAPH, 1, "source text");
        RetrievalCandidate candidate = candidate(101, 55, "candidate window");
        when(units.findActiveByIds(anyLong(), anyCollection())).thenReturn(List.of(unit));
        when(structures.findActiveByIds(anyLong(), anyCollection())).thenReturn(List.of(node));
        when(documents.findByIds(anyCollection())).thenReturn(List.of(document(23)));

        EvidenceRetrievalService service = new EvidenceRetrievalService(units, structures, documents, 8);
        var result = service.retrieve(7, List.of(candidate));

        assertEquals(1, result.primaryEvidence().size());
        assertEquals("candidate window", result.primaryEvidence().get(0).content());
        assertEquals(EvidenceOrigin.RETRIEVAL, result.primaryEvidence().get(0).origin());
        assertEquals("employee-policy.docx", result.primaryEvidence().get(0).documentName());
    }

    @Test
    void staleCandidateIsDroppedWithoutUsingNodeContentAsReplacement() {
        RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
        DocumentStructureRepository structures = mock(DocumentStructureRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        when(units.findActiveByIds(anyLong(), anyCollection())).thenReturn(List.of());

        var result = new EvidenceRetrievalService(units, structures, documents, 8)
                .retrieve(7, List.of(candidate(101, 55, "stale")));

        assertTrue(result.primaryEvidence().isEmpty());
        assertTrue(result.degradedComponents().contains("stale-retrieval-candidate"));
    }

    @Test
    void selectorDedupeUsesVersionAndNodeAndPrimaryWinsWithStableIds() {
        Evidence primary = evidence("seed", 55, NodeType.PARAGRAPH, true, .8, "primary");
        Evidence expanded = new Evidence("expanded", 7, 23, 29, 55, null, 31L, "doc",
                EvidenceOrigin.NEXT, null, NodeType.PARAGRAPH, "Path", "expanded", primary.locator(),
                .99, null, false, Map.of());
        Evidence other = evidence("other", 56, NodeType.PARAGRAPH, true, .7, "other");

        List<Evidence> selected = new EvidenceSelector(4).select(List.of(expanded, primary, other));

        assertEquals(List.of("E1", "E2"), selected.stream().map(Evidence::evidenceId).toList());
        assertEquals(List.of("primary", "other"), selected.stream().map(Evidence::content).toList());
        assertTrue(selected.get(0).primary());
    }

    @Test
    void paragraphNavigationIsPreviousAndNextAndIsBounded() {
        TestDocumentStructureRepository repository = new TestDocumentStructureRepository();
        DocumentNode root = repository.createNode(draft(7, 23, 29, null, NodeType.SECTION, 0, 0, "root", ""));
        DocumentNode previous = repository.createNode(draft(7, 23, 29, root.id(), NodeType.PARAGRAPH, 1, 0, "p0", "before"));
        DocumentNode current = repository.createNode(draft(7, 23, 29, root.id(), NodeType.PARAGRAPH, 1, 1, "p1", "current"));
        DocumentNode next = repository.createNode(draft(7, 23, 29, root.id(), NodeType.PARAGRAPH, 1, 2, "p2", "after"));
        repository.activateVersion(23, 29);

        Evidence primary = evidence("seed", current.id(), NodeType.PARAGRAPH, true, .9, "candidate");
        var result = new EvidenceExpansionService(new DocumentNavigationService(repository), 3, 3, 20, 1)
                .expand(List.of(primary));

        assertEquals(2, result.navigationActions());
        assertEquals(List.of(EvidenceOrigin.RETRIEVAL, EvidenceOrigin.PREVIOUS, EvidenceOrigin.NEXT),
                result.evidence().stream().map(Evidence::origin).toList());
        assertEquals(List.of("candidate", "before", "after"), result.evidence().stream().map(Evidence::content).toList());
        assertFalse(result.degradedComponents().contains("navigation-budget"));
    }

    @Test
    void tableRowsRespectExpansionLimitAndNavigationBudget() {
        TestDocumentStructureRepository repository = new TestDocumentStructureRepository();
        DocumentNode table = repository.createNode(draft(7, 23, 29, null, NodeType.TABLE, 0, 0, "table", "table"));
        repository.createNode(draft(7, 23, 29, table.id(), NodeType.TABLE_ROW, 1, 0, "r0", "r0"));
        repository.createNode(draft(7, 23, 29, table.id(), NodeType.TABLE_ROW, 1, 1, "r1", "r1"));
        repository.createNode(draft(7, 23, 29, table.id(), NodeType.TABLE_ROW, 1, 2, "r2", "r2"));
        repository.activateVersion(23, 29);

        Evidence primary = evidence("seed", table.id(), NodeType.TABLE, true, .9, "table candidate");
        var result = new EvidenceExpansionService(new DocumentNavigationService(repository), 3, 1, 20, 1)
                .expand(List.of(primary));

        assertEquals(1, result.navigationActions());
        assertEquals(3, result.evidence().size());
        assertEquals(List.of("table candidate", "r0", "r1"), result.evidence().stream().map(Evidence::content).toList());
        assertTrue(result.degradedComponents().contains("navigation-budget"));
    }

    private RetrievalCandidate candidate(long unitId, long nodeId, String content) {
        return new RetrievalCandidate(7, unitId, nodeId, 23, 29, 31, RetrievalUnitType.PARAGRAPH,
                "Policy", content, .9, RetrievalChannel.SEMANTIC, 1, Map.of());
    }

    private RetrievalUnit unit(long unitId, long nodeId, long buildId) {
        return new RetrievalUnit(unitId, 7, 23, 29, nodeId, buildId, RetrievalUnitType.PARAGRAPH,
                1, "Policy", "unit content", "hash", 2, Map.of(), Instant.now());
    }

    private Document document(long id) {
        return new Document(id, 7, "employee-policy.docx", "docx", "hash", null,
                "READY", null, 1);
    }

    private DocumentNode node(long id, Long parentId, NodeType type, int ordinal, String content) {
        return new DocumentNode(id, 7, 23, 29, parentId, type, parentId == null ? 0 : 1,
                ordinal, "Policy", content, "hash", 12, 12, 0L, (long) content.length(), 2,
                true, Map.of(), Instant.now());
    }

    private Evidence evidence(String id, long nodeId, NodeType type, boolean primary, double score, String content) {
        return new Evidence(id, 7, 23, 29, nodeId, primary ? 101L : null, 31L, "doc",
                primary ? EvidenceOrigin.RETRIEVAL : EvidenceOrigin.NEXT, primary ? RetrievalUnitType.PARAGRAPH : null,
                type, "Path", content, new com.modelrag.qa.evidence.EvidenceLocator("Path", 1, 1, 0L,
                        (long) content.length()), score, primary ? RetrievalChannel.SEMANTIC : null, primary, Map.of());
    }

    private com.modelrag.knowledge.model.DocumentNodeDraft draft(long dataset, long document, long version,
            Long parent, NodeType type, int depth, int ordinal, String title, String content) {
        return new com.modelrag.knowledge.model.DocumentNodeDraft(dataset, document, version, parent, type,
                depth, ordinal, title, content, "hash-" + ordinal, null, null, null, null,
                1, true, Map.of());
    }
}
