package com.modelrag.knowledge.service;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Bounded active-version navigation over the document structure tree and graph. */
@Service
public class DocumentNavigationService {
    public static final int MAX_CHILDREN = 100;
    public static final int MAX_NEIGHBORS = 20;
    public static final int MAX_ANCESTOR_DEPTH = 32;
    public static final int MAX_REFERENCES = 50;

    private final DocumentStructureRepository structures;

    public DocumentNavigationService(DocumentStructureRepository structures) {
        this.structures = structures;
    }

    public Optional<DocumentNode> open(long nodeId) {
        return structures.findActiveById(nodeId);
    }

    public Optional<DocumentNode> parent(long nodeId) {
        return structures.findActiveAncestors(nodeId, 1).stream().findFirst();
    }

    public List<DocumentNode> children(long nodeId, int offset, int limit) {
        if (limit <= 0) return List.of();
        return structures.findActiveChildren(nodeId, Math.max(0, offset), Math.min(MAX_CHILDREN, limit));
    }

    public List<DocumentNode> previous(long nodeId, int limit) {
        if (limit <= 0) return List.of();
        return structures.findActivePrevious(nodeId, Math.min(MAX_NEIGHBORS, limit));
    }

    public List<DocumentNode> next(long nodeId, int limit) {
        if (limit <= 0) return List.of();
        return structures.findActiveNext(nodeId, Math.min(MAX_NEIGHBORS, limit));
    }

    public List<DocumentNode> ancestors(long nodeId) {
        return structures.findActiveAncestors(nodeId, MAX_ANCESTOR_DEPTH);
    }

    public List<DocumentNode> references(long nodeId, int limit) {
        if (limit <= 0) return List.of();
        return structures.findReferenceTargets(nodeId, Math.min(MAX_REFERENCES, limit));
    }
}
