package com.modelrag.server;

import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Dedicated test adapter for the content-version contract. */
final class TestDocumentVersionRepository implements DocumentVersionRepository {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, List<DocumentVersion>> versions = new ConcurrentHashMap<>();

    @Override
    public synchronized DocumentVersion create(long documentId, String sourceHash, String sourceObjectKey,
            String artifactObjectKey, String contentHash, String parserName, String parserVersion,
            DocumentParseStatus parseStatus, Map<String, Object> metadata) {
        List<DocumentVersion> values = versions.computeIfAbsent(documentId, ignored -> new ArrayList<>());
        long versionNo = values.stream().mapToLong(DocumentVersion::versionNo).max().orElse(0) + 1;
        Instant now = Instant.now();
        DocumentVersion version = new DocumentVersion(ids.incrementAndGet(), documentId, versionNo, sourceHash,
                sourceObjectKey, artifactObjectKey, contentHash, parserName, parserVersion, parseStatus, metadata,
                now, parseStatus == DocumentParseStatus.READY ? now : null);
        values.add(version);
        return version;
    }

    @Override public Optional<DocumentVersion> findById(long id) {
        return versions.values().stream().flatMap(List::stream).filter(value -> value.id() == id).findFirst();
    }

    @Override public Optional<DocumentVersion> findActiveByDocumentId(long documentId) { return Optional.empty(); }

    @Override public List<DocumentVersion> findByDocumentId(long documentId) {
        return List.copyOf(versions.getOrDefault(documentId, List.of()));
    }

    @Override public void lockForStructure(long documentVersionId) {
        if (findById(documentVersionId).isEmpty()) {
            throw new IllegalArgumentException("文档版本不存在");
        }
    }
}
