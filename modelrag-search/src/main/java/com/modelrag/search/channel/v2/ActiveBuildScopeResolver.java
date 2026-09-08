package com.modelrag.search.channel.v2;

import com.modelrag.knowledge.model.ActiveBuildRef;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a bounded, PostgreSQL-derived active-build scope for V2 lexical search. */
@Component
public class ActiveBuildScopeResolver {
    private static final int DEFAULT_MAX_ACTIVE_BUILDS = 10_000;
    private final IndexBuildRepository builds;
    private final int maxActiveBuilds;

    @Autowired
    public ActiveBuildScopeResolver(IndexBuildRepository builds,
            @Value("${modelrag.retrieval.v2.max-active-build-filter:10000}") int maxActiveBuilds) {
        this.builds = builds;
        this.maxActiveBuilds = Math.min(DEFAULT_MAX_ACTIVE_BUILDS,
                maxActiveBuilds > 0 ? maxActiveBuilds : DEFAULT_MAX_ACTIVE_BUILDS);
    }

    public ActiveBuildScope resolve(long datasetId) {
        if (datasetId <= 0) return new ActiveBuildScope(List.of(), false);
        List<ActiveBuildRef> resolved = builds.findActiveByDataset(datasetId, maxActiveBuilds + 1);
        if (resolved.size() > maxActiveBuilds) return new ActiveBuildScope(List.of(), true);
        return new ActiveBuildScope(resolved, false);
    }
}
