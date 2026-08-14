package com.modelrag.knowledge.service;

import com.modelrag.api.DocumentVectorizationTool;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.knowledge.model.Document;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Framework adapter for the public ingestion SPI; the core contract remains servlet-free. */
@Service
public final class DefaultDocumentVectorizationTool implements DocumentVectorizationTool {
    private final DocumentService documents;
    private final AccessControlService access;

    public DefaultDocumentVectorizationTool(DocumentService documents, AccessControlService access) {
        this.documents = documents;
        this.access = access;
    }

    @Override
    public IngestionResult ingest(IngestionRequest request, InputStream content) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");
        if (request.userId() == null || request.userId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!request.userId().equals(access.currentUser().id())) {
            throw new SecurityException("不能以其他用户身份导入文档");
        }
        access.requireDatasetWrite(request.datasetId());
        try {
            Document document = documents.upload(request.datasetId(), request.fileName(), request.declaredMimeType(),
                    request.size(), content);
            return new IngestionResult(document.id(), document.status(), document.contentHash(),
                    document.chunkCount(), null);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("文档导入失败", error);
        }
    }

}
