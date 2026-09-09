package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.service.DocumentNavigationService;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Reads one bounded parent of an observed node. */
@Service
@Profile("!test")
public class ReadParentAction implements RetrievalActionExecutor {
    private final DocumentNavigationService navigation;
    private final int maxExcerptChars;

    public ReadParentAction(DocumentNavigationService navigation) { this(navigation, 500); }

    @Autowired
    public ReadParentAction(DocumentNavigationService navigation,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.retrieval.max-observation-excerpt-chars:500}")
            int maxExcerptChars) {
        this.navigation = navigation;
        this.maxExcerptChars = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxExcerptChars));
    }

    @Override public RetrievalActionName action() { return RetrievalActionName.READ_PARENT; }
    @Override public Set<String> allowedArguments() { return Set.of("nodeId"); }

    @Override
    public RetrievalObservation execute(RetrievalActionRequest request, RetrievalToolContext context) {
        long started = System.nanoTime();
        long nodeId = request.longInteger("nodeId");
        context.requireObservedNode(nodeId);
        if (!context.consumeNavigationAction()) {
            return RetrievalActionSupport.empty(action(), "已达到导航动作上限", List.of("navigation-budget"), started);
        }
        DocumentNode parent = navigation.parent(nodeId).orElse(null);
        if (parent == null) return RetrievalActionSupport.empty(action(), "没有可用父节点", List.of(), started);
        return RetrievalActionSupport.fromNodes(action(), nodeId, List.of(parent),
                com.modelrag.qa.evidence.EvidenceOrigin.PARENT, context, 1, maxExcerptChars, List.of(), started);
    }
}
