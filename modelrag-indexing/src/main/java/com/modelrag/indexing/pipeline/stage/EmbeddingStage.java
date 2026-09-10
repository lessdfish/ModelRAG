package com.modelrag.indexing.pipeline.stage;

import com.modelrag.indexing.pipeline.IndexBuildContext;
import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.knowledge.model.RetrievalUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/** Remote embedding boundary; callers invoke it outside database transactions. */
@Service
public class EmbeddingStage {
    private final TextEmbeddingProvider embeddings;

    public EmbeddingStage(TextEmbeddingProvider embeddings) { this.embeddings = embeddings; }

    public List<float[]> embed(IndexBuildContext context, List<RetrievalUnit> units) {
        if (units == null || units.isEmpty()) return List.of();
        return embeddings.embedBatch(context.datasetId(), units.stream().map(RetrievalUnit::content).toList());
    }
}
