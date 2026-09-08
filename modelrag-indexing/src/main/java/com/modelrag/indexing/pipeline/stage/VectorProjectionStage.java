package com.modelrag.indexing.pipeline.stage;

import com.modelrag.indexing.pipeline.IndexBuildContext;
import com.modelrag.indexing.store.RetrievalEmbeddingRepository;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Pages units, performs remote embedding, then persists bounded V2 vector batches. */
@Service
public class VectorProjectionStage {
    private static final int PAGE_SIZE = 100;
    private final RetrievalUnitRepository units;
    private final RetrievalEmbeddingRepository embeddings;
    private final EmbeddingStage embeddingStage;

    public VectorProjectionStage(RetrievalUnitRepository units, RetrievalEmbeddingRepository embeddings,
            EmbeddingStage embeddingStage) {
        this.units = units;
        this.embeddings = embeddings;
        this.embeddingStage = embeddingStage;
    }

    public long project(IndexBuildContext context) {
        for (int offset = 0;; offset += PAGE_SIZE) {
            List<RetrievalUnit> page = units.findByBuild(context.buildId(), offset, PAGE_SIZE);
            if (page.isEmpty()) break;
            List<float[]> vectors = embeddingStage.embed(context, page);
            if (vectors.size() != page.size()) throw new IllegalStateException("Embedding 返回数量不匹配");
            Map<Long, float[]> byUnit = new LinkedHashMap<>();
            for (int index = 0; index < page.size(); index++) byUnit.put(page.get(index).id(), vectors.get(index));
            embeddings.upsertBatch(context.buildId(), context.embeddingProfile(), byUnit);
            if (page.size() < PAGE_SIZE) break;
        }
        return embeddings.countByBuild(context.buildId());
    }
}
