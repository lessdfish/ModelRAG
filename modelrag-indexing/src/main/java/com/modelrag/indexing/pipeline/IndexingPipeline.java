package com.modelrag.indexing.pipeline;

import com.modelrag.common.event.ReembedDatasetEvent;
import com.modelrag.common.exception.SafeErrorSummary;
import com.modelrag.knowledge.model.IndexBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Compatibility facade: V1 remains authoritative while V2 is an isolated shadow build. */
@Service
public class IndexingPipeline {
    private static final Logger LOG = LoggerFactory.getLogger(IndexingPipeline.class);
    private final LegacyChunkIndexingPipeline legacy;
    private final IndexBuildCoordinator v2;
    private final boolean shadowEnabled;

    @Autowired
    public IndexingPipeline(LegacyChunkIndexingPipeline legacy, IndexBuildCoordinator v2,
            @Value("${modelrag.index.v2.shadow-enabled:false}") boolean shadowEnabled) {
        this.legacy = legacy;
        this.v2 = v2;
        this.shadowEnabled = shadowEnabled;
    }

    /** Source-compatible constructor for direct legacy tests and callers. */
    public IndexingPipeline(com.modelrag.knowledge.repository.DatasetRepository datasets,
            com.modelrag.knowledge.repository.DocumentRepository documents,
            com.modelrag.knowledge.repository.ChunkRepository chunks,
            com.modelrag.knowledge.repository.IndexVersionRepository versions,
            com.modelrag.indexing.service.EmbeddingService embed,
            com.modelrag.common.vector.VectorStore vectors,
            org.springframework.context.ApplicationEventPublisher events,
            com.modelrag.common.sse.SseEmitterService sse,
            com.modelrag.common.outbox.IndexOutbox outbox,
            com.modelrag.knowledge.service.ObjectStorageService storage,
            org.springframework.transaction.support.TransactionOperations transaction,
            @org.springframework.beans.factory.annotation.Qualifier("embeddingExecutor") java.util.concurrent.ExecutorService executor) {
        this.legacy = new LegacyChunkIndexingPipeline(datasets, documents, chunks, versions, embed, vectors, events, sse,
                outbox, storage, transaction, executor);
        this.v2 = null;
        this.shadowEnabled = false;
    }

    @EventListener
    public void reembed(ReembedDatasetEvent event) { rebuildDataset(event.datasetId()); }

    public int reembedDataset(long datasetId) { return legacy.reembedDataset(datasetId); }

    public int rebuildDataset(long datasetId) { return legacy.rebuildDataset(datasetId); }

    /** Keeps the old void-style entrypoint while making the V1 outcome explicit internally. */
    public void index(long documentId) {
        LegacyIndexResult result = legacy.index(documentId);
        if (!result.success() || !shadowEnabled || v2 == null) return;
        try {
            IndexBuild build = v2.start(documentId);
            if (build.state() == com.modelrag.knowledge.model.IndexBuildState.FAILED) {
                LOG.warn("V2 shadow build failed without affecting V1: documentId={}, buildId={}, error={}",
                        documentId, build.id(), build.errorMsg());
            }
        } catch (Exception error) {
            LOG.warn("V2 shadow build could not start; V1 remains successful: documentId={}, error={}",
                    documentId, SafeErrorSummary.of(error));
        }
    }
}
