package com.modelrag.qa.evidence;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.search.dto.RetrievalCandidate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Converts only active, source-validated V2 candidates into primary evidence. */
@Service
public class EvidenceRetrievalService {
    private final RetrievalUnitRepository units;
    private final DocumentStructureRepository structures;
    private final DocumentRepository documents;
    private final int maxPrimaryEvidence;

    public EvidenceRetrievalService(RetrievalUnitRepository units,
            DocumentStructureRepository structures, DocumentRepository documents) {
        this(units, structures, documents, 8);
    }

    @Autowired
    public EvidenceRetrievalService(RetrievalUnitRepository units,
            DocumentStructureRepository structures, DocumentRepository documents,
            @Value("${modelrag.qa.v2.max-primary-evidence:8}") int maxPrimaryEvidence) {
        this.units = units;
        this.structures = structures;
        this.documents = documents;
        this.maxPrimaryEvidence = Math.max(1, Math.min(32, maxPrimaryEvidence));
    }

    public EvidenceRetrievalResult retrieve(long datasetId, List<RetrievalCandidate> candidates) {
        if (datasetId <= 0 || candidates == null || candidates.isEmpty()) {
            return new EvidenceRetrievalResult(List.of(), List.of(), 0);
        }
        List<String> degraded = new ArrayList<>();
        if (candidates.size() > 100) degraded.add("retrieval-candidate-budget");
        List<RetrievalCandidate> bounded = candidates.stream().filter(candidate -> candidate != null)
                .limit(100).toList();
        LinkedHashSet<Long> candidateIds = bounded.stream()
                .map(RetrievalCandidate::retrievalUnitId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<RetrievalUnit> activeUnits;
        try {
            activeUnits = units.findActiveByIds(datasetId, candidateIds);
        } catch (RuntimeException error) {
            degraded.add("active-retrieval-unit-validation");
            return new EvidenceRetrievalResult(List.of(), degraded, bounded.size());
        }
        Map<Long, RetrievalUnit> unitsById = activeUnits.stream()
                .filter(unit -> unit != null && unit.datasetId() == datasetId)
                .collect(java.util.stream.Collectors.toMap(RetrievalUnit::id, unit -> unit, (left, right) -> left,
                        LinkedHashMap::new));
        LinkedHashSet<Long> nodeIds = new LinkedHashSet<>();
        for (RetrievalCandidate candidate : bounded) {
            RetrievalUnit unit = unitsById.get(candidate.retrievalUnitId());
            if (matches(candidate, unit)) nodeIds.add(unit.nodeId());
        }
        List<DocumentNode> activeNodes;
        try {
            activeNodes = structures.findActiveByIds(datasetId, nodeIds);
        } catch (RuntimeException error) {
            degraded.add("active-source-node-validation");
            return new EvidenceRetrievalResult(List.of(), degraded, bounded.size());
        }
        Map<Long, DocumentNode> nodesById = activeNodes.stream()
                .filter(node -> node != null && node.datasetId() == datasetId)
                .collect(java.util.stream.Collectors.toMap(DocumentNode::id, node -> node, (left, right) -> left,
                        LinkedHashMap::new));
        LinkedHashSet<Long> documentIds = new LinkedHashSet<>();
        for (RetrievalCandidate candidate : bounded) {
            RetrievalUnit unit = unitsById.get(candidate.retrievalUnitId());
            DocumentNode node = unit == null ? null : nodesById.get(unit.nodeId());
            if (matches(candidate, unit) && matches(candidate, unit, node)) documentIds.add(unit.documentId());
        }
        Map<Long, String> names = documentNames(documentIds);
        List<Evidence> result = new ArrayList<>();
        int dropped = 0;
        for (RetrievalCandidate candidate : bounded) {
            RetrievalUnit unit = unitsById.get(candidate.retrievalUnitId());
            DocumentNode node = unit == null ? null : nodesById.get(unit.nodeId());
            if (!matches(candidate, unit) || !matches(candidate, unit, node)) {
                dropped++;
                continue;
            }
            if (result.size() >= maxPrimaryEvidence) break;
            String titlePath = blank(candidate.titlePath()) ? firstNonBlank(node.title(), "") : candidate.titlePath();
            result.add(new Evidence("candidate-" + candidate.retrievalUnitId(), datasetId,
                    unit.documentId(), unit.documentVersionId(), unit.nodeId(), unit.id(), unit.indexBuildId(),
                    names.getOrDefault(unit.documentId(), "文档"), EvidenceOrigin.RETRIEVAL, unit.unitType(),
                    node.nodeType(), titlePath, candidate.content(),
                    new EvidenceLocator(titlePath, node.pageFrom(), node.pageTo(), node.charStart(), node.charEnd()),
                    candidate.score(), candidate.channel(), true, candidate.metadata()));
        }
        if (dropped > 0 || activeUnits.size() < bounded.size()) degraded.add("stale-retrieval-candidate");
        if (names.size() < documentIds.size()) degraded.add("document-name-lookup");
        return new EvidenceRetrievalResult(List.copyOf(result), List.copyOf(degraded), dropped);
    }

    private Map<Long, String> documentNames(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return Map.of();
        try {
            return documents.findByIds(documentIds).stream().filter(document -> document != null)
                    .collect(java.util.stream.Collectors.toMap(com.modelrag.knowledge.model.Document::id,
                            document -> blank(document.fileName()) ? "文档" : document.fileName(),
                            (left, right) -> left, LinkedHashMap::new));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private boolean matches(RetrievalCandidate candidate, RetrievalUnit unit) {
        return unit != null && unit.id() == candidate.retrievalUnitId()
                && unit.datasetId() == candidate.datasetId()
                && unit.documentId() == candidate.documentId()
                && unit.documentVersionId() == candidate.documentVersionId()
                && unit.nodeId() == candidate.nodeId()
                && unit.indexBuildId() == candidate.indexBuildId()
                && unit.unitType() == candidate.unitType();
    }

    private boolean matches(RetrievalCandidate candidate, RetrievalUnit unit, DocumentNode node) {
        return node != null && matches(candidate, unit)
                && node.id() == unit.nodeId() && node.datasetId() == unit.datasetId()
                && node.documentId() == unit.documentId()
                && node.documentVersionId() == unit.documentVersionId();
    }

    private String firstNonBlank(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record EvidenceRetrievalResult(List<Evidence> primaryEvidence,
            List<String> degradedComponents, int droppedCandidates) {
        public EvidenceRetrievalResult {
            primaryEvidence = primaryEvidence == null ? List.of() : List.copyOf(primaryEvidence);
            degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream().distinct().toList();
            if (droppedCandidates < 0) throw new IllegalArgumentException("droppedCandidates must not be negative");
        }
    }
}
