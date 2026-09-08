package com.modelrag.indexing.pipeline.stage;

import com.modelrag.indexing.store.RetrievalEmbeddingRepository;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Derives final build counts from bounded repositories before a V2 build can become READY. */
@Service
public class IndexVerificationStage {
    private final DocumentRepository documents;
    private final DocumentStructureRepository structures;
    private final RetrievalUnitRepository units;
    private final RetrievalEmbeddingRepository embeddings;

    public IndexVerificationStage(DocumentRepository documents, DocumentStructureRepository structures,
            RetrievalUnitRepository units, RetrievalEmbeddingRepository embeddings) {
        this.documents = documents;
        this.structures = structures;
        this.units = units;
        this.embeddings = embeddings;
    }

    public VerificationResult verify(IndexBuild build, long lexicalCount) {
        if (build == null || build.id() <= 0) throw new IllegalArgumentException("IndexBuild 无效");
        Document document = documents.findById(build.documentId());
        if (document.activeVersionId() == null || !Objects.equals(document.activeVersionId(), build.documentVersionId())) {
            throw new IllegalStateException("IndexBuild 文档版本已过期");
        }
        long nodeCount = structures.countByVersion(build.documentVersionId());
        long unitCount = units.countByBuild(build.id());
        long vectorCount = embeddings.countByBuild(build.id());
        if (nodeCount <= 0 || unitCount <= 0) throw new IllegalStateException("V2 构建没有结构或检索单元");
        if (vectorCount != unitCount) throw new IllegalStateException("V2 向量数量与检索单元数量不一致");
        if (lexicalCount != unitCount) throw new IllegalStateException("V2 词法投影未完全同步");
        if (build.nodeCount() > 0 && build.nodeCount() != nodeCount) {
            throw new IllegalStateException("V2 结构数量发生变化");
        }
        if (build.unitCount() > 0 && build.unitCount() != unitCount) {
            throw new IllegalStateException("V2 检索单元数量发生变化");
        }
        if (build.vectorCount() > 0 && build.vectorCount() != vectorCount) {
            throw new IllegalStateException("V2 向量数量发生变化");
        }
        if (build.lexicalCount() > 0 && build.lexicalCount() != lexicalCount) {
            throw new IllegalStateException("V2 词法投影数量发生变化");
        }
        return new VerificationResult(nodeCount, unitCount, vectorCount, lexicalCount);
    }

    public record VerificationResult(long nodeCount, long unitCount, long vectorCount, long lexicalCount) { }
}
