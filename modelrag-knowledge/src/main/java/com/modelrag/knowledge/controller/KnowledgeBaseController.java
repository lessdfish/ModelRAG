package com.modelrag.knowledge.controller;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.event.ReembedDatasetEvent;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.RequestUser;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeStore store;
    private final IndexOutbox outbox;
    private final ApplicationEventPublisher events;
    private final AccessControlService access;
    private final DocumentDeletionService deletion;

    public KnowledgeBaseController(KnowledgeStore store, IndexOutbox outbox,
            ApplicationEventPublisher events, AccessControlService access, DocumentDeletionService deletion) {
        this.store = store;
        this.outbox = outbox;
        this.events = events;
        this.access = access;
        this.deletion = deletion;
    }

    @GetMapping
    public ApiResponse<List<Dataset>> list() {
        RequestUser user = access.currentUser();
        List<Dataset> datasets = store.datasets();
        return ApiResponse.success(user.roles().contains("ADMIN") ? datasets
                : datasets.stream().filter(dataset -> user.datasetIds().contains(dataset.id())).toList());
    }

    @GetMapping("/{datasetId}")
    public ApiResponse<Dataset> get(@PathVariable long datasetId) {
        access.requireDatasetAccess(datasetId);
        return ApiResponse.success(store.dataset(datasetId));
    }

    @PostMapping
    public ApiResponse<Dataset> create(@Valid @RequestBody DatasetMutationRequest request) {
        access.requireRole("ADMIN");
        return ApiResponse.success(store.createDataset(request.name(), request.description(), request.chunkSize(),
                request.chunkOverlap(), request.topK(), request.thresholdValue()));
    }

    @PutMapping("/{datasetId}")
    public ApiResponse<Dataset> update(@PathVariable long datasetId, @Valid @RequestBody DatasetMutationRequest request) {
        access.requireDatasetAdmin(datasetId);
        Dataset before = store.dataset(datasetId);
        Dataset updated = store.updateDataset(datasetId, request.name(), request.description(),
                request.chunkSize() == null ? before.chunkSize() : request.chunkSize(),
                request.chunkOverlap() == null ? before.chunkOverlap() : request.chunkOverlap(),
                request.topK() == null ? before.topK() : request.topK(),
                request.thresholdValue() == null ? before.threshold() : request.thresholdValue());
        if (before.chunkSize() != updated.chunkSize() || before.chunkOverlap() != updated.chunkOverlap()) {
            events.publishEvent(new ReembedDatasetEvent(datasetId));
        }
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{datasetId}")
    public ApiResponse<Void> delete(@PathVariable long datasetId) {
        access.requireDatasetAdmin(datasetId);
        deletion.deleteDataset(datasetId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{datasetId}/rebuild-index")
    public ApiResponse<RebuildIndexView> rebuild(@PathVariable long datasetId) {
        access.requireDatasetWrite(datasetId);
        store.dataset(datasetId);
        events.publishEvent(new ReembedDatasetEvent(datasetId));
        return ApiResponse.success(new RebuildIndexView(store.documents(datasetId).size(), outbox.requeueDataset(datasetId)));
    }
}
