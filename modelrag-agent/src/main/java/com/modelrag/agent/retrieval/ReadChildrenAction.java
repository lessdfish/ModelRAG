package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.service.DocumentNavigationService;
import com.modelrag.qa.evidence.EvidenceOrigin;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Reads at most twenty active children of an observed node. */
@Service
@Profile("!test")
public class ReadChildrenAction implements RetrievalActionExecutor {
    public static final int MAX_LIMIT = 20;
    public static final int MAX_OFFSET = 1_000;
    private final DocumentNavigationService navigation;
    private final int maxExcerptChars;

    public ReadChildrenAction(DocumentNavigationService navigation) { this(navigation, 500); }

    @Autowired
    public ReadChildrenAction(DocumentNavigationService navigation,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-excerpt-chars:500}")
            int maxExcerptChars) {
        this.navigation = navigation;
        this.maxExcerptChars = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxExcerptChars));
    }

    @Override public RetrievalActionName action() { return RetrievalActionName.READ_CHILDREN; }
    @Override public Set<String> allowedArguments() { return Set.of("nodeId", "offset", "limit"); }

    @Override
    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        long started = System.nanoTime();
        long nodeId = request.longInteger("nodeId");
        context.requireObservedNode(nodeId);
        int offset = request.integer("offset", 0);
        int limit = request.integer("limit", 10);
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("offset must be between 0 and 1000");
        }
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 20");
        if (!context.consumeNavigationAction()) {
            return RetrievalActionSupport.empty(action(), "已达到导航动作上限", List.of("navigation-budget"), started);
        }
        return RetrievalActionSupport.fromNodes(action(), nodeId, navigation.children(nodeId, offset, limit),
                EvidenceOrigin.CHILD, context, MAX_LIMIT, maxExcerptChars, List.of(), started);
    }
}
