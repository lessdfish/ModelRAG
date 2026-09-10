package com.modelrag.server.eval;

import com.modelrag.knowledge.repository.ChunkRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Resolves legacy chunk labels to documents without inventing V2 nodes or evidence groups. */
public class EvaluationLabelResolver {
    private final ChunkRepository chunks;

    public EvaluationLabelResolver(ChunkRepository chunks) { this.chunks = chunks; }

    public EvalLabels resolve(long datasetId, EvalItem item) {
        List<Long> expectedChunks = item == null ? List.of() : item.expectedChunkIds();
        List<Long> documents = item == null ? List.of() : item.expectedDocumentIds();
        boolean legacyDocuments = documents == null || documents.isEmpty();
        if (legacyDocuments && expectedChunks != null && !expectedChunks.isEmpty()) {
            LinkedHashSet<Long> resolved = new LinkedHashSet<>();
            chunks.findActiveByIds(datasetId, expectedChunks).forEach(chunk -> resolved.add(chunk.documentId()));
            documents = new ArrayList<>(resolved);
        }
        List<Long> nodes = item == null ? List.of() : item.expectedNodeIds();
        List<EvalEvidenceGroup> groups = item == null ? List.of() : item.expectedEvidenceGroups();
        String documentStatus = documents == null || documents.isEmpty()
                ? EvalLabels.INSUFFICIENT_LABELS : (legacyDocuments ? EvalLabels.LEGACY_LABEL_ONLY : EvalLabels.COMPARABLE);
        String nodeStatus = nodes == null || nodes.isEmpty() ? EvalLabels.INSUFFICIENT_LABELS : EvalLabels.COMPARABLE;
        String evidenceStatus = groups == null || groups.isEmpty() ? EvalLabels.INSUFFICIENT_LABELS : EvalLabels.COMPARABLE;
        return new EvalLabels(expectedChunks, documents, nodes, groups, documentStatus, nodeStatus, evidenceStatus);
    }
}
