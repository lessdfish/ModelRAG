package com.modelrag.knowledge.controller;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.event.DocumentUploadedEvent;
import com.modelrag.common.event.ReembedDatasetEvent;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.rate.DatasetRateLimiter;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.knowledge.service.DocumentService;
import com.modelrag.knowledge.service.DocumentDeletionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2")
public class DatasetV2Controller {
    private final KnowledgeStore store;
    private final DocumentService documents;
    private final IndexOutbox outbox;
    private final DocumentDeletionService deletion;
    private final ApplicationEventPublisher events;
    private final AccessControlService access;
    private final DatasetRateLimiter limiter;

    public DatasetV2Controller(KnowledgeStore store, DocumentService documents, IndexOutbox outbox,
            DocumentDeletionService deletion, ApplicationEventPublisher events, AccessControlService access,
            DatasetRateLimiter limiter) {
        this.store = store;
        this.documents = documents;
        this.outbox = outbox;
        this.deletion = deletion;
        this.events = events;
        this.access = access;
        this.limiter = limiter;
    }

    @GetMapping("/datasets")
    public ApiResponse<List<Dataset>> list() {
        var user = access.currentUser();
        List<Dataset> result = store.datasets();
        return ApiResponse.success(user.hasRole("ADMIN") ? result
                : result.stream().filter(item -> user.datasetIds().contains(item.id())).toList());
    }

    @GetMapping("/datasets/{datasetId}")
    public ApiResponse<Dataset> get(@PathVariable long datasetId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(store.dataset(datasetId));
    }

    @PostMapping("/datasets")
    public ApiResponse<Dataset> create(@Valid @RequestBody DatasetMutationRequest request) {
        access.requireRole("ADMIN");
        return ApiResponse.success(store.createDataset(request.name(), request.description(), request.chunkSize(),
                request.chunkOverlap(), request.topK(), request.thresholdValue()));
    }

    @PutMapping("/datasets/{datasetId}")
    public ApiResponse<Dataset> update(@PathVariable long datasetId, @Valid @RequestBody DatasetMutationRequest request) {
        access.requireDatasetAdmin(datasetId);
        Dataset before = store.dataset(datasetId);
        Dataset updated = store.updateDataset(datasetId, request.name(), request.description(), request.chunkSize(),
                request.chunkOverlap(), request.topK(), request.thresholdValue());
        if (before.chunkSize() != updated.chunkSize() || before.chunkOverlap() != updated.chunkOverlap()) {
            events.publishEvent(new ReembedDatasetEvent(datasetId));
        }
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/datasets/{datasetId}")
    public ApiResponse<Void> delete(@PathVariable long datasetId) {
        access.requireDatasetAdmin(datasetId);
        deletion.deleteDataset(datasetId);
        return ApiResponse.success(null);
    }

    @PostMapping("/datasets/{datasetId}/rebuild")
    public ApiResponse<RebuildIndexView> rebuild(@PathVariable long datasetId) {
        access.requireDatasetWrite(datasetId);
        limiter.checkUpload(access.currentUser().id());
        store.dataset(datasetId);
        events.publishEvent(new ReembedDatasetEvent(datasetId));
        return ApiResponse.success(new RebuildIndexView(store.documents(datasetId).size(),
                outbox.requeueDataset(datasetId)));
    }

    @PostMapping("/datasets/{datasetId}/documents")
    public ApiResponse<com.modelrag.knowledge.model.Document> upload(@PathVariable long datasetId,
            @RequestParam("file") MultipartFile file) throws Exception {
        access.requireDatasetWrite(datasetId);
        limiter.checkUpload(access.currentUser().id());
        store.dataset(datasetId);
        return ApiResponse.success(documents.upload(datasetId, file));
    }

    @GetMapping("/datasets/{datasetId}/documents")
    public ApiResponse<List<com.modelrag.knowledge.model.Document>> documents(@PathVariable long datasetId) {
        access.requireDatasetAccess(datasetId);
        store.dataset(datasetId);
        return ApiResponse.success(store.documents(datasetId));
    }

    @PostMapping("/documents/{documentId}/reindex")
    public ApiResponse<com.modelrag.knowledge.model.Document> reindex(@PathVariable long documentId) {
        var document = store.document(documentId);
        access.requireDatasetWrite(document.datasetId());
        events.publishEvent(new DocumentUploadedEvent(this, document.id(), document.datasetId()));
        return ApiResponse.success(store.document(documentId));
    }

    @GetMapping("/documents/{documentId}/index-status")
    public ApiResponse<IndexStatus> indexStatus(@PathVariable long documentId) {
        var document = store.document(documentId);
        access.requireDatasetAccess(document.datasetId());
        return ApiResponse.success(new IndexStatus(document.id(), document.datasetId(), document.status(),
                document.error(), document.chunkCount()));
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable long documentId) {
        var document = store.document(documentId);
        access.requireDatasetWrite(document.datasetId());
        deletion.deleteDocument(document.datasetId(), documentId);
        return ApiResponse.success(null);
    }

    @GetMapping("/datasets/{datasetId}/documents/{documentId}/chunks")
    public ApiResponse<List<com.modelrag.knowledge.model.Chunk>> chunks(@PathVariable long datasetId,
            @PathVariable long documentId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(store.chunks(datasetId).stream()
                .filter(chunk -> chunk.documentId() == documentId).toList());
    }

    public record IndexStatus(long documentId, long datasetId, String status, String error, int chunkCount) {}
}
