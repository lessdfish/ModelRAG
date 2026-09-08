package com.modelrag.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.IndexVersionRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcChunkRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcDatasetRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcDocumentRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcIndexVersionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @deprecated Production code now injects the four narrow JDBC repositories.
 *             This compatibility facade is retained for one migration step
 *             for direct legacy callers and tests; it is not a Spring bean.
 */
@Deprecated
public class PostgresKnowledgeStore implements KnowledgeStore {
    private final DatasetRepository datasets;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final IndexVersionRepository versions;

    public PostgresKnowledgeStore(JdbcTemplate jdbc, TextEmbeddingProvider embeddings) {
        this(new JdbcDatasetRepository(jdbc, embeddings), new JdbcDocumentRepository(jdbc),
                new JdbcChunkRepository(jdbc, new ObjectMapper()), new JdbcIndexVersionRepository(jdbc));
    }

    public PostgresKnowledgeStore(DatasetRepository datasets, DocumentRepository documents, ChunkRepository chunks,
            IndexVersionRepository versions) {
        this.datasets = datasets;
        this.documents = documents;
        this.chunks = chunks;
        this.versions = versions;
    }

    @Override public Dataset createDataset(String name, String description, Integer chunkSize, Integer chunkOverlap) {
        return datasets.create(name, description, chunkSize, chunkOverlap);
    }

    @Override public Dataset createDataset(String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold) {
        return datasets.create(name, description, chunkSize, chunkOverlap, topK, threshold);
    }

    @Override public List<Dataset> datasets() { return datasets.findAll(); }

    @Override public List<Dataset> routeDatasets(String query, Set<Long> allowedDatasetIds, int limit) {
        return datasets.route(query, allowedDatasetIds, limit);
    }

    @Override public Set<Long> indexedDatasetIds() { return datasets.findIndexedDatasetIds(); }

    @Override public Dataset dataset(long id) { return datasets.findById(id); }

    @Override public Dataset updateDataset(long id, String name, String description) {
        return datasets.update(id, name, description);
    }

    @Override public Dataset updateDataset(long id, String name, String description, Integer chunkSize,
            Integer chunkOverlap, Integer topK, Double threshold) {
        return datasets.update(id, name, description, chunkSize, chunkOverlap, topK, threshold);
    }

    @Override public void deleteDataset(long id) {
        datasets.findById(id);
        datasets.softDelete(id);
        documents.softDeleteByDatasetId(id);
        chunks.softDeleteByDatasetId(id);
    }

    @Override public Document addDocument(long datasetId, String name, String type, String hash, String content) {
        return addDocument(datasetId, name, type, hash, content, null, null, hash);
    }

    @Override public Document addDocument(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash) {
        datasets.findById(datasetId);
        Document document = documents.create(datasetId, name, type, hash, content,
                sourceObjectKey, artifactObjectKey, contentHash);
        datasets.bumpRevision(datasetId);
        return document;
    }

    @Override public Document document(long id) { return documents.findById(id); }

    @Override public void deleteDocument(long datasetId, long documentId) {
        Document document = documents.findById(documentId);
        if (document.datasetId() != datasetId) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        documents.softDelete(documentId);
        chunks.softDeleteByDocumentId(documentId);
        datasets.bumpRevision(datasetId);
    }

    @Override public void status(long id, String status, String error, int chunkCount) {
        documents.updateStatus(id, status, error, chunkCount);
    }

    @Override public long beginIndexVersion(long documentId) { return versions.begin(documentId); }
    @Override public void activateIndexVersion(long documentId, long version) { versions.activate(documentId, version); }
    @Override public Map<Long, Long> activeIndexVersions(long datasetId) {
        return versions.findActiveByDatasetId(datasetId);
    }
    @Override public Map<Long, Long> allActiveIndexVersions() { return versions.findAllActive(); }
    @Override public List<Document> documents(long datasetId) { return documents.findByDatasetId(datasetId); }
    @Override public List<Chunk> chunks(long datasetId) { return chunks.findActiveByDatasetId(datasetId); }
    @Override public List<Chunk> findChunksByIds(long datasetId, Collection<Long> ids) {
        return chunks.findActiveByIds(datasetId, ids);
    }
    @Override public List<Chunk> findChunksByParentIds(long datasetId, Collection<Long> parentIds) {
        return chunks.findActiveByParentIds(datasetId, parentIds);
    }
    @Override public List<Chunk> findChunkNeighbors(long datasetId, Collection<KnowledgeStore.ChunkWindow> windows) {
        List<com.modelrag.knowledge.repository.ChunkWindow> bounded = windows == null ? List.of() : windows.stream()
                .map(window -> new com.modelrag.knowledge.repository.ChunkWindow(
                        window.documentId(), window.fromIndex(), window.toIndex()))
                .toList();
        return chunks.findActiveNeighbors(datasetId, bounded);
    }

    @Override public void chunks(long documentId, List<Chunk> values) {
        chunks.replaceDocumentVersion(documentId, values);
        datasets.bumpRevision(documents.findById(documentId).datasetId());
    }

    @Override public void beginChunks(long documentId, long version) {
        chunks.beginDocumentVersion(documentId, version);
    }

    @Override public void appendChunks(long documentId, List<Chunk> values) {
        chunks.append(documentId, values);
    }

    @Override public long nextId() { return chunks.nextId(); }
    @Override public Dataset bumpDatasetRevision(long datasetId) { return datasets.bumpRevision(datasetId); }
}
