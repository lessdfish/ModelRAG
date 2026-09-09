package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.service.DocumentNavigationService;
import com.modelrag.qa.evidence.EvidenceOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Reads at most two previous and two next active siblings around an observed node. */
@Service
@Profile("!test")
public class ReadNeighborsAction implements RetrievalActionExecutor {
    public static final int MAX_RADIUS = 2;
    private final DocumentNavigationService navigation;
    private final int maxExcerptChars;

    public ReadNeighborsAction(DocumentNavigationService navigation) { this(navigation, 500); }

    @Autowired
    public ReadNeighborsAction(DocumentNavigationService navigation,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-excerpt-chars:500}")
            int maxExcerptChars) {
        this.navigation = navigation;
        this.maxExcerptChars = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxExcerptChars));
    }

    @Override public RetrievalActionName action() { return RetrievalActionName.READ_NEIGHBORS; }
    @Override public Set<String> allowedArguments() { return Set.of("nodeId", "radius"); }

    @Override
    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        long started = System.nanoTime();
        long nodeId = request.longInteger("nodeId");
        context.requireObservedNode(nodeId);
        int radius = request.integer("radius", 1);
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("radius must be between 1 and 2");
        if (!context.consumeNavigationAction()) {
            return RetrievalActionSupport.empty(action(), "已达到导航动作上限", List.of("navigation-budget"), started);
        }
        return RetrievalActionSupport.fromNodeGroups(action(), nodeId, List.of(
                new RetrievalActionSupport.NodeGroup(EvidenceOrigin.PREVIOUS, navigation.previous(nodeId, radius)),
                new RetrievalActionSupport.NodeGroup(EvidenceOrigin.NEXT, navigation.next(nodeId, radius))),
                context, RetrievalObservation.MAX_ITEMS, maxExcerptChars, List.of(), started);
    }
}
