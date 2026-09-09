package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.service.DocumentNavigationService;
import com.modelrag.qa.evidence.EvidenceOrigin;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Follows one bounded hop of active references from an observed node. */
@Service
@Profile("!test")
public class FollowReferencesAction implements RetrievalActionExecutor {
    public static final int MAX_LIMIT = 10;
    private final DocumentNavigationService navigation;
    private final int maxExcerptChars;

    public FollowReferencesAction(DocumentNavigationService navigation) { this(navigation, 500); }

    @Autowired
    public FollowReferencesAction(DocumentNavigationService navigation,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-excerpt-chars:500}")
            int maxExcerptChars) {
        this.navigation = navigation;
        this.maxExcerptChars = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxExcerptChars));
    }

    @Override public RetrievalActionName action() { return RetrievalActionName.FOLLOW_REFERENCES; }
    @Override public Set<String> allowedArguments() { return Set.of("nodeId", "limit"); }

    @Override
    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        long started = System.nanoTime();
        long nodeId = request.longInteger("nodeId");
        context.requireObservedNode(nodeId);
        int limit = request.integer("limit", 5);
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 10");
        if (!context.consumeNavigationAction()) {
            return RetrievalActionSupport.empty(action(), "已达到导航动作上限", List.of("navigation-budget"), started);
        }
        return RetrievalActionSupport.fromReferencedNodes(action(), nodeId, navigation.references(nodeId, limit),
                context, MAX_LIMIT, maxExcerptChars, List.of(), started);
    }
}
