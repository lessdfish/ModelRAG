package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.search.channel.v2.DocumentLexicalSearchRequest;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.dto.RetrievalCandidate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Resolves one document's active V2 build before issuing an indexed lexical lookup. */
@Service
@Profile("!test")
public class DocumentLexicalFindService {
    private static final int MAX_CANDIDATES = 20;

    private final DocumentRepository documents;
    private final IndexBuildRepository builds;
    private final RetrievalUnitRepository units;
    private final LexicalSearchPort lexical;

    public DocumentLexicalFindService(DocumentRepository documents, IndexBuildRepository builds,
            RetrievalUnitRepository units, LexicalSearchPort lexical) {
        this.documents = documents;
        this.builds = builds;
        this.units = units;
        this.lexical = lexical;
    }

    public FindResult find(RetrievalToolContext context, long documentId, String query, int limit) {
        context.requireObservedDocument(documentId);
        int boundedLimit = Math.max(1, Math.min(MAX_CANDIDATES, limit));
        Document document = documents.findById(documentId);
        if (document.datasetId() != context.datasetId()) {
            throw new IllegalArgumentException("document belongs to another dataset");
        }
        IndexBuild build = builds.findActiveByDocumentId(documentId).orElse(null);
        if (build == null || build.state() != IndexBuildState.ACTIVE
                || build.datasetId() != context.datasetId() || build.documentId() != documentId
                || document.activeVersionId() == null
                || document.activeVersionId() != build.documentVersionId()
                || document.activeIndexBuildId() == null
                || document.activeIndexBuildId() != build.id()) {
            return new FindResult(List.of(), List.of("active-v2-build-unavailable"));
        }

        List<RetrievalCandidate> candidates = lexical.findInDocument(new DocumentLexicalSearchRequest(
                context.datasetId(), documentId, query, List.of(build.id()), boundedLimit));
        if (candidates == null || candidates.isEmpty()) return new FindResult(List.of(), List.of());
        List<RetrievalCandidate> bounded = candidates.stream().filter(value -> value != null)
                .filter(value -> value.documentId() == documentId && value.datasetId() == context.datasetId()
                        && value.documentVersionId() == build.documentVersionId()
                        && value.indexBuildId() == build.id())
                .limit(MAX_CANDIDATES).toList();
        if (bounded.isEmpty()) return new FindResult(List.of(), List.of("stale-retrieval-candidate"));

        // The ES adapter performs the same validation, but this batch check keeps
        // the action safe when a different LexicalSearchPort implementation is used.
        LinkedHashSet<Long> ids = bounded.stream().map(RetrievalCandidate::retrievalUnitId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, RetrievalUnit> active = new LinkedHashMap<>();
        for (RetrievalUnit unit : units.findActiveByIds(context.datasetId(), ids)) {
            if (unit != null) active.put(unit.id(), unit);
        }
        List<RetrievalCandidate> validated = bounded.stream().filter(candidate -> matches(candidate, active.get(
                candidate.retrievalUnitId()), build)).toList();
        List<String> degraded = validated.size() == bounded.size() ? List.of() : List.of("stale-retrieval-candidate");
        return new FindResult(validated, degraded);
    }

    private boolean matches(RetrievalCandidate candidate, RetrievalUnit unit, IndexBuild build) {
        return unit != null && unit.id() == candidate.retrievalUnitId()
                && unit.datasetId() == candidate.datasetId() && unit.documentId() == candidate.documentId()
                && unit.documentVersionId() == candidate.documentVersionId()
                && unit.indexBuildId() == candidate.indexBuildId() && unit.indexBuildId() == build.id()
                && unit.unitType() == candidate.unitType();
    }

    public record FindResult(List<RetrievalCandidate> candidates, List<String> degradedComponents) {
        public FindResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream()
                    .filter(value -> value != null && !value.isBlank()).distinct().toList();
        }
    }
}
