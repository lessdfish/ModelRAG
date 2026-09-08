package com.modelrag.server;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.DocumentNodeDraft;
import com.modelrag.knowledge.model.NodeEdge;
import com.modelrag.knowledge.model.NodeEdgeDraft;
import com.modelrag.knowledge.model.NodeEdgeType;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Dedicated in-memory adapter for bounded structure/navigation tests. */
final class TestDocumentStructureRepository implements DocumentStructureRepository {
    private final AtomicLong nodeIds = new AtomicLong();
    private final AtomicLong edgeIds = new AtomicLong();
    private final Map<Long, DocumentNode> nodes = new HashMap<>();
    private final Map<Long, NodeEdge> edges = new HashMap<>();
    private final Map<Long, Long> activeVersions = new HashMap<>();

    @Override
    public synchronized DocumentNode createNode(DocumentNodeDraft draft) {
        if (draft == null || draft.nodeType() == null || draft.depth() < 0 || draft.ordinal() < 0
                || draft.tokenCount() < 0) throw new BusinessException(ErrorCode.VALIDATION, "节点参数无效");
        if (draft.parentId() == null && draft.depth() != 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "根节点深度必须为 0");
        }
        if (draft.parentId() != null) {
            DocumentNode parent = nodes.get(draft.parentId());
            if (parent == null) throw new BusinessException(ErrorCode.NOT_FOUND, "父节点不存在");
            if (parent.datasetId() != draft.datasetId() || parent.documentId() != draft.documentId()
                    || parent.documentVersionId() != draft.documentVersionId()) {
                throw new BusinessException(ErrorCode.VALIDATION, "父节点必须属于同一文档版本");
            }
            if (draft.depth() != parent.depth() + 1) {
                throw new BusinessException(ErrorCode.VALIDATION, "子节点深度无效");
            }
        }
        if (nodes.values().stream().anyMatch(node -> node.documentVersionId() == draft.documentVersionId()
                && node.parentId() == null && draft.parentId() == null)) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "文档版本根节点已存在");
        }
        if (draft.parentId() != null && nodes.values().stream().anyMatch(node ->
                node.documentVersionId() == draft.documentVersionId() && draft.parentId().equals(node.parentId())
                        && node.ordinal() == draft.ordinal())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "兄弟节点序号已存在");
        }
        long id = nodeIds.incrementAndGet();
        DocumentNode node = new DocumentNode(id, draft.datasetId(), draft.documentId(), draft.documentVersionId(),
                draft.parentId(), draft.nodeType(), draft.depth(), draft.ordinal(), draft.title(), draft.content(),
                draft.contentHash(), draft.pageFrom(), draft.pageTo(), draft.charStart(), draft.charEnd(),
                draft.tokenCount(), draft.searchable(), draft.metadata(), Instant.now());
        nodes.put(id, node);
        activeVersions.putIfAbsent(node.documentId(), node.documentVersionId());
        return node;
    }

    @Override
    public synchronized Optional<DocumentNode> findRootByVersion(long documentVersionId) {
        return nodes.values().stream().filter(node -> node.documentVersionId() == documentVersionId
                && node.parentId() == null).findFirst();
    }

    @Override
    public synchronized long countByVersion(long documentVersionId) {
        return nodes.values().stream().filter(node -> node.documentVersionId() == documentVersionId).count();
    }

    @Override
    public synchronized List<DocumentNode> findByVersion(long documentVersionId, int offset, int limit) {
        if (limit <= 0) return List.of();
        return nodes.values().stream().filter(node -> node.documentVersionId() == documentVersionId)
                .sorted(Comparator.comparingInt(DocumentNode::depth).thenComparingInt(DocumentNode::ordinal)
                        .thenComparingLong(DocumentNode::id))
                .skip(Math.max(0, offset)).limit(Math.min(100, limit)).toList();
    }

    @Override public synchronized Optional<DocumentNode> findById(long nodeId) { return Optional.ofNullable(nodes.get(nodeId)); }

    @Override public synchronized Optional<DocumentNode> findActiveById(long nodeId) {
        DocumentNode node = nodes.get(nodeId);
        return isActive(node) ? Optional.of(node) : Optional.empty();
    }

    @Override public synchronized List<DocumentNode> findActiveChildren(long parentNodeId, int offset, int limit) {
        if (limit <= 0) return List.of();
        return activeNodes().stream().filter(node -> java.util.Objects.equals(node.parentId(), parentNodeId))
                .sorted(Comparator.comparingInt(DocumentNode::ordinal)).skip(Math.max(0, offset)).limit(limit).toList();
    }

    @Override public synchronized List<DocumentNode> findActivePrevious(long nodeId, int limit) {
        DocumentNode center = activeNodes().stream().filter(node -> node.id() == nodeId).findFirst().orElse(null);
        if (center == null || limit <= 0) return List.of();
        return activeNodes().stream().filter(node -> sameScope(center, node) && node.ordinal() < center.ordinal())
                .sorted(Comparator.comparingInt(DocumentNode::ordinal).reversed()).limit(limit)
                .sorted(Comparator.comparingInt(DocumentNode::ordinal)).toList();
    }

    @Override public synchronized List<DocumentNode> findActiveNext(long nodeId, int limit) {
        DocumentNode center = activeNodes().stream().filter(node -> node.id() == nodeId).findFirst().orElse(null);
        if (center == null || limit <= 0) return List.of();
        return activeNodes().stream().filter(node -> sameScope(center, node) && node.ordinal() > center.ordinal())
                .sorted(Comparator.comparingInt(DocumentNode::ordinal)).limit(limit).toList();
    }

    @Override public synchronized List<DocumentNode> findActiveAncestors(long nodeId, int maxDepth) {
        if (maxDepth <= 0) return List.of();
        DocumentNode current = nodes.get(nodeId);
        if (!isActive(current)) return List.of();
        List<DocumentNode> result = new ArrayList<>();
        int depth = 0;
        while (current != null && current.parentId() != null && depth++ < maxDepth) {
            current = nodes.get(current.parentId());
            if (current == null || !isActive(current)) break;
            result.add(0, current);
        }
        return List.copyOf(result);
    }

    @Override public synchronized NodeEdge createEdge(NodeEdgeDraft draft) {
        if (draft == null || draft.edgeType() == null || !nodes.containsKey(draft.fromNodeId())
                || !nodes.containsKey(draft.toNodeId())) throw new BusinessException(ErrorCode.NOT_FOUND, "节点边端点不存在");
        if (edges.values().stream().anyMatch(edge -> edge.fromNodeId() == draft.fromNodeId()
                && edge.toNodeId() == draft.toNodeId() && edge.edgeType() == draft.edgeType())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "节点边已存在");
        }
        NodeEdge edge = new NodeEdge(edgeIds.incrementAndGet(), draft.fromNodeId(), draft.toNodeId(),
                draft.edgeType(), draft.metadata(), Instant.now());
        edges.put(edge.id(), edge);
        return edge;
    }

    @Override public synchronized List<NodeEdge> findOutgoingEdges(long nodeId, Set<NodeEdgeType> types, int limit) {
        if (limit <= 0 || types == null || types.isEmpty() || !isActive(nodes.get(nodeId))) return List.of();
        return edges.values().stream().filter(edge -> edge.fromNodeId() == nodeId && types.contains(edge.edgeType())
                        && isActive(nodes.get(edge.toNodeId())))
                .sorted(Comparator.comparingLong(NodeEdge::id)).limit(limit).toList();
    }

    @Override public synchronized List<DocumentNode> findReferenceTargets(long nodeId, int limit) {
        if (limit <= 0 || !isActive(nodes.get(nodeId))) return List.of();
        return edges.values().stream().filter(edge -> edge.fromNodeId() == nodeId
                        && edge.edgeType() == NodeEdgeType.REFERENCE && isActive(nodes.get(edge.toNodeId())))
                .sorted(Comparator.comparingLong(NodeEdge::id)).map(edge -> nodes.get(edge.toNodeId())).limit(limit).toList();
    }

    void activateVersion(long documentId, long documentVersionId) { activeVersions.put(documentId, documentVersionId); }

    private boolean isActive(DocumentNode node) {
        return node != null && activeVersions.getOrDefault(node.documentId(), node.documentVersionId()) == node.documentVersionId();
    }

    private List<DocumentNode> activeNodes() { return nodes.values().stream().filter(this::isActive).toList(); }

    private boolean sameScope(DocumentNode center, DocumentNode candidate) {
        return center.documentId() == candidate.documentId()
                && center.documentVersionId() == candidate.documentVersionId()
                && java.util.Objects.equals(center.parentId(), candidate.parentId());
    }
}
