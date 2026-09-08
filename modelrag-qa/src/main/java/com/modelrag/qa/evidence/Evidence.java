package com.modelrag.qa.evidence;

import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.search.dto.RetrievalChannel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime, citation-ready evidence. It is deliberately independent of legacy Chunk. */
public record Evidence(String evidenceId, long datasetId, long documentId, long documentVersionId,
        long nodeId, Long retrievalUnitId, Long indexBuildId, String documentName,
        EvidenceOrigin origin, RetrievalUnitType unitType, NodeType nodeType, String titlePath,
        String content, EvidenceLocator locator, double score, RetrievalChannel channel,
        boolean primary, Map<String, Object> metadata) {
    public Evidence {
        if (evidenceId == null || evidenceId.isBlank() || datasetId <= 0 || documentId <= 0
                || documentVersionId <= 0 || nodeId <= 0 || origin == null || nodeType == null) {
            throw new IllegalArgumentException("evidence identity is invalid");
        }
        if (retrievalUnitId != null && retrievalUnitId <= 0) {
            throw new IllegalArgumentException("retrievalUnitId must be positive");
        }
        if (indexBuildId != null && indexBuildId <= 0) {
            throw new IllegalArgumentException("indexBuildId must be positive");
        }
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            throw new IllegalArgumentException("evidence score is invalid");
        }
        documentName = documentName == null || documentName.isBlank() ? "文档" : documentName;
        titlePath = titlePath == null ? "" : titlePath;
        content = content == null ? "" : content;
        locator = locator == null ? EvidenceLocator.empty() : locator;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public Evidence withEvidenceId(String id) {
        return new Evidence(id, datasetId, documentId, documentVersionId, nodeId, retrievalUnitId,
                indexBuildId, documentName, origin, unitType, nodeType, titlePath, content, locator,
                score, channel, primary, metadata);
    }

    public String sourceKey() {
        return documentVersionId + ":" + nodeId;
    }
}
