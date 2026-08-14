package com.modelrag.common.event;

import org.springframework.context.ApplicationEvent;

public final class IndexingCompletedEvent extends ApplicationEvent {
    private final long documentId;
    private final boolean success;
    private final String error;

    public IndexingCompletedEvent(Object source, long documentId, boolean success, String error) {
        super(source);
        this.documentId = documentId;
        this.success = success;
        this.error = error;
    }

    public long documentId() {
        return documentId;
    }

    public boolean success() {
        return success;
    }

    public String error() {
        return error;
    }
}
