package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.DocumentNodeDraft;
import com.modelrag.knowledge.model.NodeEdgeDraft;
import com.modelrag.knowledge.model.NodeEdgeType;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.service.DocumentNavigationService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentNavigationServiceTest {
    @Test
    void navigationIsPagedBoundedAndStaysWithinSiblingScope() {
        TestDocumentStructureRepository repository = new TestDocumentStructureRepository();
        DocumentNavigationService navigation = new DocumentNavigationService(repository);
        DocumentNode root = repository.createNode(draft(1, 10, 100, null, 0, 0, NodeType.DOCUMENT,
                Map.of("structure", Map.of("kind", "root"))));
        assertEquals(Map.of("structure", Map.of("kind", "root")), root.metadata());
        for (int ordinal = 0; ordinal <= 120; ordinal++) {
            repository.createNode(draft(1, 10, 100, root.id(), 1, ordinal, NodeType.PARAGRAPH, Map.of()));
        }
        DocumentNode center = repository.findActiveChildren(root.id(), 10, 1).get(0);
        assertEquals(10, center.ordinal());
        assertEquals(List.of(7, 8, 9), navigation.previous(center.id(), 3).stream()
                .map(DocumentNode::ordinal).toList());
        assertEquals(List.of(11, 12, 13), navigation.next(center.id(), 3).stream()
                .map(DocumentNode::ordinal).toList());
        assertEquals(100, navigation.children(root.id(), 10, 1_000).size());
        assertEquals(10, navigation.children(root.id(), 10, 1_000).get(0).ordinal());
        assertEquals(109, navigation.children(root.id(), 10, 1_000).get(99).ordinal());
    }

    @Test
    void ancestorsAreRootFirstAndInactiveVersionsAreHidden() {
        TestDocumentStructureRepository repository = new TestDocumentStructureRepository();
        DocumentNavigationService navigation = new DocumentNavigationService(repository);
        DocumentNode root = repository.createNode(draft(1, 10, 100, null, 0, 0, NodeType.DOCUMENT, Map.of()));
        DocumentNode section = repository.createNode(draft(1, 10, 100, root.id(), 1, 0, NodeType.SECTION, Map.of()));
        DocumentNode paragraph = repository.createNode(draft(1, 10, 100, section.id(), 2, 0, NodeType.PARAGRAPH, Map.of()));
        assertEquals(List.of(root.id(), section.id()), navigation.ancestors(paragraph.id()).stream()
                .map(DocumentNode::id).toList());

        DocumentNode nextVersionRoot = repository.createNode(
                draft(1, 10, 101, null, 0, 0, NodeType.DOCUMENT, Map.of()));
        assertTrue(navigation.open(root.id()).isPresent());
        assertTrue(navigation.open(nextVersionRoot.id()).isEmpty());
        repository.activateVersion(10, 101);
        assertTrue(navigation.open(root.id()).isEmpty());
        assertTrue(navigation.open(nextVersionRoot.id()).isPresent());
    }

    @Test
    void referencesCanCrossDocumentsAndDuplicateEdgesAreRejected() {
        TestDocumentStructureRepository repository = new TestDocumentStructureRepository();
        DocumentNavigationService navigation = new DocumentNavigationService(repository);
        DocumentNode source = repository.createNode(draft(1, 10, 100, null, 0, 0, NodeType.DOCUMENT, Map.of()));
        DocumentNode target = repository.createNode(draft(2, 20, 200, null, 0, 0, NodeType.DOCUMENT, Map.of()));
        NodeEdgeDraft edge = new NodeEdgeDraft(source.id(), target.id(), NodeEdgeType.REFERENCE,
                Map.of("confidence", .9));
        repository.createEdge(edge);
        assertEquals(List.of(target.id()), navigation.references(source.id(), 500).stream()
                .map(DocumentNode::id).toList());
        assertThrows(BusinessException.class, () -> repository.createEdge(edge));
    }

    @Test
    void crossVersionTreeParentAndDuplicateRootAreRejected() {
        TestDocumentStructureRepository repository = new TestDocumentStructureRepository();
        DocumentNode root = repository.createNode(draft(1, 10, 100, null, 0, 0, NodeType.DOCUMENT, Map.of()));
        assertThrows(BusinessException.class,
                () -> repository.createNode(draft(1, 10, 100, null, 0, 1, NodeType.DOCUMENT, Map.of())));
        assertThrows(BusinessException.class,
                () -> repository.createNode(draft(1, 10, 101, root.id(), 1, 0, NodeType.SECTION, Map.of())));
    }

    @Test
    void navigationHasNoLegacyChunkDependency() {
        assertFalse(Arrays.stream(DocumentNavigationService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getName().contains("ChunkRepository")));
    }

    private DocumentNodeDraft draft(long datasetId, long documentId, long versionId, Long parentId,
            int depth, int ordinal, NodeType type, Map<String, Object> metadata) {
        return new DocumentNodeDraft(datasetId, documentId, versionId, parentId, type, depth, ordinal,
                null, null, null, null, null, null, null, 0, true, metadata);
    }
}
