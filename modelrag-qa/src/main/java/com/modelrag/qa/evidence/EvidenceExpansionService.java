package com.modelrag.qa.evidence;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.service.DocumentNavigationService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Performs only small, active-tree navigation around bounded primary evidence. */
@Service
public class EvidenceExpansionService {
    private final DocumentNavigationService navigation;
    private final int maxExpandedEvidence;
    private final int maxNavigationActions;
    private final int maxTableRows;
    private final int neighborRadius;

    public EvidenceExpansionService(DocumentNavigationService navigation) {
        this(navigation, 16, 24, 20, 1);
    }

    @Autowired
    public EvidenceExpansionService(DocumentNavigationService navigation,
            @Value("${modelrag.qa.v2.max-expanded-evidence:16}") int maxExpandedEvidence,
            @Value("${modelrag.qa.v2.max-navigation-actions:24}") int maxNavigationActions,
            @Value("${modelrag.qa.v2.max-table-rows:20}") int maxTableRows,
            @Value("${modelrag.qa.v2.neighbor-radius:1}") int neighborRadius) {
        this.navigation = navigation;
        this.maxExpandedEvidence = Math.max(1, Math.min(EvidenceSet.MAX_EVIDENCE, maxExpandedEvidence));
        this.maxNavigationActions = Math.max(0, Math.min(128, maxNavigationActions));
        this.maxTableRows = Math.max(0, Math.min(100, maxTableRows));
        this.neighborRadius = Math.max(0, Math.min(3, neighborRadius));
    }

    public ExpansionResult expand(List<Evidence> primaryEvidence) {
        List<Evidence> result = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        if (primaryEvidence == null || primaryEvidence.isEmpty()) {
            return new ExpansionResult(List.of(), 0, List.of());
        }
        int actions = 0;
        for (Evidence primary : primaryEvidence) {
            if (primary == null || result.size() >= maxExpandedEvidence) break;
            result.add(primary);
            NodeType type = primary.nodeType();
            if (isParagraphLike(type)) {
                NavigationBatch previous = previous(primary, actions);
                actions = previous.actions();
                degraded.addAll(previous.degraded());
                result.addAll(addWithinBudget(primary, previous.nodes(), EvidenceOrigin.PREVIOUS, result));
                if (actions < maxNavigationActions && result.size() < maxExpandedEvidence) {
                    NavigationBatch next = next(primary, actions);
                    actions = next.actions();
                    degraded.addAll(next.degraded());
                    result.addAll(addWithinBudget(primary, next.nodes(), EvidenceOrigin.NEXT, result));
                }
            } else if (type == NodeType.TABLE) {
                NavigationBatch children = children(primary, actions, maxTableRows);
                actions = children.actions();
                degraded.addAll(children.degraded());
                result.addAll(addWithinBudget(primary, children.nodes().stream()
                        .filter(node -> node.nodeType() == NodeType.TABLE_ROW).limit(maxTableRows).toList(),
                        EvidenceOrigin.CHILD, result));
            } else if (type == NodeType.SECTION) {
                NavigationBatch parent = parent(primary, actions);
                actions = parent.actions();
                degraded.addAll(parent.degraded());
                result.addAll(addWithinBudget(primary, parent.nodes(), EvidenceOrigin.PARENT, result));
                if (actions < maxNavigationActions && result.size() < maxExpandedEvidence) {
                    NavigationBatch children = children(primary, actions, 2);
                    actions = children.actions();
                    degraded.addAll(children.degraded());
                    result.addAll(addWithinBudget(primary, children.nodes(), EvidenceOrigin.CHILD, result));
                }
            }
            if (actions >= maxNavigationActions && hasMoreNavigation(primary)) degraded.add("navigation-budget");
        }
        if (actions >= maxNavigationActions) degraded.add("navigation-budget");
        return new ExpansionResult(List.copyOf(result), actions, degraded.stream().distinct().toList());
    }

    private NavigationBatch previous(Evidence primary, int actions) {
        if (actions >= maxNavigationActions || neighborRadius == 0) return new NavigationBatch(List.of(), actions, List.of());
        try {
            return new NavigationBatch(navigation.previous(primary.nodeId(), neighborRadius), actions + 1, List.of());
        } catch (RuntimeException error) {
            return new NavigationBatch(List.of(), actions + 1, List.of("navigation-previous"));
        }
    }

    private NavigationBatch next(Evidence primary, int actions) {
        if (actions >= maxNavigationActions || neighborRadius == 0) return new NavigationBatch(List.of(), actions, List.of());
        try {
            return new NavigationBatch(navigation.next(primary.nodeId(), neighborRadius), actions + 1, List.of());
        } catch (RuntimeException error) {
            return new NavigationBatch(List.of(), actions + 1, List.of("navigation-next"));
        }
    }

    private NavigationBatch children(Evidence primary, int actions, int limit) {
        if (actions >= maxNavigationActions || limit <= 0) return new NavigationBatch(List.of(), actions, List.of());
        try {
            return new NavigationBatch(navigation.children(primary.nodeId(), 0, limit), actions + 1, List.of());
        } catch (RuntimeException error) {
            return new NavigationBatch(List.of(), actions + 1, List.of("navigation-children"));
        }
    }

    private NavigationBatch parent(Evidence primary, int actions) {
        if (actions >= maxNavigationActions) return new NavigationBatch(List.of(), actions, List.of());
        try {
            return new NavigationBatch(navigation.parent(primary.nodeId()).stream().toList(), actions + 1, List.of());
        } catch (RuntimeException error) {
            return new NavigationBatch(List.of(), actions + 1, List.of("navigation-parent"));
        }
    }

    private List<Evidence> addWithinBudget(Evidence primary, List<DocumentNode> nodes,
            EvidenceOrigin origin, List<Evidence> current) {
        if (nodes == null || nodes.isEmpty() || current.size() >= maxExpandedEvidence) return List.of();
        List<Evidence> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Evidence value : current) seen.add(value.sourceKey());
        for (DocumentNode node : nodes) {
            if (node == null || result.size() + current.size() >= maxExpandedEvidence) break;
            if (!sameSource(primary, node)) continue;
            String key = node.documentVersionId() + ":" + node.id();
            if (!seen.add(key)) continue;
            String titlePath = blank(primary.titlePath()) ? node.title() : primary.titlePath();
            result.add(new Evidence("expanded-" + origin.name().toLowerCase() + "-" + node.id(),
                    primary.datasetId(), node.documentId(), node.documentVersionId(), node.id(), null,
                    primary.indexBuildId(), primary.documentName(), origin, null, node.nodeType(), titlePath,
                    node.content(), new EvidenceLocator(titlePath, node.pageFrom(), node.pageTo(),
                            node.charStart(), node.charEnd()), primary.score(), null, false, node.metadata()));
            seen.add(key);
        }
        return List.copyOf(result);
    }

    private boolean sameSource(Evidence primary, DocumentNode node) {
        return node.id() > 0 && node.datasetId() == primary.datasetId()
                && node.documentId() == primary.documentId()
                && node.documentVersionId() == primary.documentVersionId();
    }

    private boolean isParagraphLike(NodeType type) {
        return type == NodeType.PARAGRAPH || type == NodeType.LIST || type == NodeType.LIST_ITEM
                || type == NodeType.QUOTE || type == NodeType.CODE || type == NodeType.CAPTION
                || type == NodeType.FOOTNOTE;
    }

    private boolean hasMoreNavigation(Evidence primary) {
        return isParagraphLike(primary.nodeType()) || primary.nodeType() == NodeType.TABLE
                || primary.nodeType() == NodeType.SECTION;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record NavigationBatch(List<DocumentNode> nodes, int actions, List<String> degraded) {
        private NavigationBatch {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            degraded = degraded == null ? List.of() : List.copyOf(degraded);
        }
    }

    public record ExpansionResult(List<Evidence> evidence, int navigationActions,
            List<String> degradedComponents) {
        public ExpansionResult {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            degradedComponents = degradedComponents == null ? List.of() : degradedComponents.stream().distinct().toList();
            if (navigationActions < 0) throw new IllegalArgumentException("navigationActions must not be negative");
        }
    }
}
