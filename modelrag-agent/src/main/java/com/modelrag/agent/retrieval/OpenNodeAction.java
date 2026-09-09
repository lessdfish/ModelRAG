package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.service.DocumentNavigationService;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Opens one already observed active node and returns bounded structural evidence. */
@Service
@Profile("!test")
public class OpenNodeAction implements RetrievalActionExecutor {
    private final DocumentNavigationService navigation;
    private final int maxExcerptChars;

    public OpenNodeAction(DocumentNavigationService navigation) {
        this(navigation, 500);
    }

    @Autowired
    public OpenNodeAction(DocumentNavigationService navigation,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-excerpt-chars:500}")
            int maxExcerptChars) {
        this.navigation = navigation;
        this.maxExcerptChars = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxExcerptChars));
    }

    @Override public RetrievalActionName action() { return RetrievalActionName.OPEN_NODE; }
    @Override public Set<String> allowedArguments() { return Set.of("nodeId"); }

    @Override
    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        long started = System.nanoTime();
        long nodeId = request.longInteger("nodeId");
        context.requireObservedNode(nodeId);
        if (!context.consumeNavigationAction()) {
            return RetrievalActionSupport.empty(action(), "已达到导航动作上限", List.of("navigation-budget"), started);
        }
        DocumentNode node = navigation.open(nodeId).orElse(null);
        if (node == null) return RetrievalActionSupport.empty(action(), "节点不可用或已不是当前活动版本",
                List.of("stale-node"), started);
        return RetrievalActionSupport.fromNodes(action(), nodeId, List.of(node),
                com.modelrag.qa.evidence.EvidenceOrigin.REFERENCE, context, 1, maxExcerptChars, List.of(), started);
    }
}
