package com.modelrag.knowledge.controller;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.rate.DatasetRateLimiter;
import com.modelrag.common.sse.SseEmitterService;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.service.DocumentService;
import com.modelrag.knowledge.service.DocumentDeletionService;
import com.modelrag.knowledge.service.KnowledgeStore;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{datasetId}/documents")
public class DocumentController {
    private final DocumentService documents;
    private final KnowledgeStore store;
    private final SseEmitterService sse;
    private final DocumentDeletionService deletion;
    private final AccessControlService access;
    private final DatasetRateLimiter limiter;

    public DocumentController(DocumentService documents, KnowledgeStore store, SseEmitterService sse,
            DocumentDeletionService deletion, AccessControlService access, DatasetRateLimiter limiter) {
        this.documents = documents;
        this.store = store;
        this.sse = sse;
        this.deletion = deletion;
        this.access = access;
        this.limiter = limiter;
    }

    @PostMapping
    public ApiResponse<Document> upload(@PathVariable long datasetId, @RequestParam MultipartFile file) throws Exception {
        access.requireDatasetWrite(datasetId);
        limiter.checkUpload(access.currentUser().id());
        return ApiResponse.success(documents.upload(datasetId, file));
    }

    @GetMapping
    public ApiResponse<List<Document>> list(@PathVariable long datasetId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(store.documents(datasetId));
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> delete(@PathVariable long datasetId, @PathVariable long documentId) {
        access.requireDatasetWrite(datasetId);
        deletion.deleteDocument(datasetId, documentId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{documentId}/chunks")
    public ApiResponse<List<Chunk>> chunks(@PathVariable long datasetId, @PathVariable long documentId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(store.chunks(datasetId).stream()
                .filter(chunk -> chunk.documentId() == documentId).toList());
    }

    @GetMapping(value = "/{documentId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long datasetId, @PathVariable long documentId) {
        access.requireDatasetAccess(datasetId);
        store.document(documentId);
        return sse.subscribe("document:" + documentId);
    }
}
