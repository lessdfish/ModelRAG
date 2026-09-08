package com.modelrag.search.channel.v2;

import com.modelrag.knowledge.model.ActiveBuildRef;
import java.util.List;

/** Resolved PostgreSQL active-build filter, including an explicit overflow state. */
public record ActiveBuildScope(List<ActiveBuildRef> builds, boolean overflow) {
    public ActiveBuildScope {
        builds = builds == null ? List.of() : List.copyOf(builds);
    }

    public List<Long> indexBuildIds() {
        return builds.stream().map(ActiveBuildRef::indexBuildId).distinct().toList();
    }
}
