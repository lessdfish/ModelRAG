package com.modelrag.indexing.pipeline.stage;

import com.modelrag.indexing.outbox.RetrievalProjectionOutbox;
import com.modelrag.indexing.pipeline.IndexBuildContext;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Appends paged V2 retrieval units to the dedicated lexical outbox without calling Elasticsearch. */
@Service
@Profile("!test")
public class LexicalProjectionStage {
    private static final int PAGE_SIZE = 100;
    private final RetrievalUnitRepository units;
    private final RetrievalProjectionOutbox outbox;

    public LexicalProjectionStage(RetrievalUnitRepository units, RetrievalProjectionOutbox outbox) {
        this.units = units;
        this.outbox = outbox;
    }

    public long project(IndexBuildContext context) {
        long projected = 0;
        for (int offset = 0;; offset += PAGE_SIZE) {
            List<RetrievalUnit> page = units.findByBuild(context.buildId(), offset, PAGE_SIZE);
            if (page.isEmpty()) break;
            outbox.appendBatch(context.buildId(), page);
            projected += page.size();
            if (page.size() < PAGE_SIZE) break;
        }
        return projected;
    }
}
