package com.modelrag.indexing.pipeline;

import com.modelrag.common.exception.SafeErrorSummary;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.service.IndexBuildLifecycleService;
import com.modelrag.indexing.pipeline.stage.DocumentParseStage;
import com.modelrag.indexing.pipeline.stage.LexicalProjectionStage;
import com.modelrag.indexing.pipeline.stage.RetrievalUnitBuildStage;
import com.modelrag.indexing.pipeline.stage.StructurePersistStage;
import com.modelrag.indexing.pipeline.stage.VectorProjectionStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Orchestrates the synchronous, pre-lexical portion of one V2 shadow build. */
@Service
public class IndexBuildCoordinator {
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentStructureRepository structures;
    private final IndexBuildRepository builds;
    private final IndexBuildLifecycleService lifecycle;
    private final DocumentParseStage parse;
    private final StructurePersistStage persist;
    private final RetrievalUnitBuildStage unitBuild;
    private final VectorProjectionStage vectors;
    private final LexicalProjectionStage lexical;
    private final String embeddingProfile;
    private final String rerankProfile;

    @Autowired
    public IndexBuildCoordinator(DocumentRepository documents, DocumentVersionRepository versions,
            DocumentStructureRepository structures, IndexBuildRepository builds,
            IndexBuildLifecycleService lifecycle, DocumentParseStage parse, StructurePersistStage persist,
            RetrievalUnitBuildStage unitBuild, VectorProjectionStage vectors,
            LexicalProjectionStage lexical,
            @Value("${modelrag.index.v2.embedding-profile:qwen3-v1}") String embeddingProfile,
            @Value("${modelrag.index.v2.rerank-profile:default}") String rerankProfile) {
        this.documents = documents;
        this.versions = versions;
        this.structures = structures;
        this.builds = builds;
        this.lifecycle = lifecycle;
        this.parse = parse;
        this.persist = persist;
        this.unitBuild = unitBuild;
        this.vectors = vectors;
        this.lexical = lexical;
        this.embeddingProfile = embeddingProfile;
        this.rerankProfile = rerankProfile;
    }

    /** Starts V2 from the current immutable version and leaves lexical delivery to the async worker. */
    public IndexBuild start(long documentId) {
        Document document = documents.findById(documentId);
        DocumentVersion version = activeVersion(document);
        IndexBuild build = lifecycle.createBuild(document.datasetId(), document.id(), version.id(),
                embeddingProfile, rerankProfile, java.util.Map.of("source", "g4-shadow"));
        try {
            lifecycle.transition(build.id(), IndexBuildState.CREATED, IndexBuildState.PARSING);
            if (structures.findRootByVersion(version.id()).isEmpty()) {
                persist.persist(document.datasetId(), document.id(), version.id(), parse.parse(document, version));
            }
            long nodeCount = structures.countByVersion(version.id());
            lifecycle.updateCounts(build.id(), nodeCount, 0, 0, 0);
            lifecycle.transition(build.id(), IndexBuildState.PARSING, IndexBuildState.STRUCTURE_READY);

            IndexBuildContext context = context(build, version);
            lifecycle.transition(build.id(), IndexBuildState.STRUCTURE_READY, IndexBuildState.UNIT_BUILDING);
            long unitCount = unitBuild.build(context, document.fileName());
            lifecycle.updateCounts(build.id(), nodeCount, unitCount, 0, 0);
            lifecycle.transition(build.id(), IndexBuildState.UNIT_BUILDING, IndexBuildState.UNIT_READY);

            lifecycle.transition(build.id(), IndexBuildState.UNIT_READY, IndexBuildState.VECTOR_BUILDING);
            long vectorCount = vectors.project(context.withCounts(nodeCount, unitCount, 0, 0));
            lifecycle.updateCounts(build.id(), nodeCount, unitCount, vectorCount, 0);
            lifecycle.transition(build.id(), IndexBuildState.VECTOR_BUILDING, IndexBuildState.VECTOR_READY);
            lifecycle.transition(build.id(), IndexBuildState.VECTOR_READY, IndexBuildState.LEXICAL_SYNCING);
            lexical.project(context.withCounts(nodeCount, unitCount, vectorCount, 0));
        } catch (Exception error) {
            lifecycle.fail(build.id(), SafeErrorSummary.of(error));
        }
        return builds.findById(build.id()).orElse(build);
    }

    private DocumentVersion activeVersion(Document document) {
        if (document.activeVersionId() != null) {
            return versions.findById(document.activeVersionId()).orElseThrow(
                    () -> new IllegalStateException("活动文档版本不存在"));
        }
        return versions.findActiveByDocumentId(document.id()).orElseThrow(
                () -> new IllegalStateException("活动文档版本不存在"));
    }

    private IndexBuildContext context(IndexBuild build, DocumentVersion version) {
        return new IndexBuildContext(build.id(), build.datasetId(), build.documentId(), build.documentVersionId(),
                build.embeddingProfile(), build.rerankProfile(), version.sourceObjectKey(), build.nodeCount(),
                build.unitCount(), build.vectorCount(), build.lexicalCount());
    }
}
