package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.DocumentNodeDraft;
import com.modelrag.knowledge.model.NodeEdge;
import com.modelrag.knowledge.model.NodeEdgeDraft;
import com.modelrag.knowledge.model.NodeEdgeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence contract for immutable document structure. */
public interface DocumentStructureRepository {
    DocumentNode createNode(DocumentNodeDraft node);

    Optional<DocumentNode> findById(long nodeId);

    Optional<DocumentNode> findActiveById(long nodeId);

    List<DocumentNode> findActiveChildren(long parentNodeId, int offset, int limit);

    List<DocumentNode> findActivePrevious(long nodeId, int limit);

    List<DocumentNode> findActiveNext(long nodeId, int limit);

    List<DocumentNode> findActiveAncestors(long nodeId, int maxDepth);

    NodeEdge createEdge(NodeEdgeDraft edge);

    List<NodeEdge> findOutgoingEdges(long nodeId, Set<NodeEdgeType> types, int limit);

    List<DocumentNode> findReferenceTargets(long nodeId, int limit);
}
