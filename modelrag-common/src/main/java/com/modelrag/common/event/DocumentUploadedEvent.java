package com.modelrag.common.event;

import org.springframework.context.ApplicationEvent;

public final class DocumentUploadedEvent extends ApplicationEvent {
    private final long documentId;
    private final long datasetId;

    public DocumentUploadedEvent(Object source, long documentId, long datasetId) {
        super(source);
        this.documentId = documentId;
        this.datasetId = datasetId;
    }

    public long documentId() {
        return documentId;
    }

    public long datasetId() {
        return datasetId;
    }
}
