package com.modelrag.agent.retrieval;

import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceLocator;
import com.modelrag.qa.evidence.EvidenceOrigin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Package-local construction helpers that keep action output bounded and source-scoped. */
final class RetrievalActionSupport {
    private RetrievalActionSupport() { }

    static RetrievalObservation empty(RetrievalActionName action, String summary,
            List<String> degraded, long started) {
        return new RetrievalObservation(action, summary, List.of(), List.of(), degraded, elapsed(started));
    }

    static RetrievalObservation fromEvidence(RetrievalActionName action, List<Evidence> evidence,
            int maxItems, int maxExcerptChars, List<String> degraded, long started) {
        List<Evidence> bounded = evidence == null ? List.of() : evidence.stream()
                .filter(value -> value != null).limit(maxItems).map(value -> bounded(value, maxExcerptChars)).toList();
        List<RetrievalObservationItem> items = bounded.stream().map(value -> item(value, maxExcerptChars)).toList();
        return new RetrievalObservation(action, "返回 " + bounded.size() + " 个结构化证据项",
                items, bounded, degraded, elapsed(started));
    }

    static RetrievalObservation fromNodes(RetrievalActionName action, long sourceNodeId,
            List<DocumentNode> nodes, EvidenceOrigin origin, RetrievalToolContext context,
            int maxItems, int maxExcerptChars, List<String> degraded, long started) {
        return fromNodeGroups(action, sourceNodeId, List.of(new NodeGroup(origin, nodes)), context,
                maxItems, maxExcerptChars, degraded, started, true);
    }

    static RetrievalObservation fromReferencedNodes(RetrievalActionName action, long sourceNodeId,
            List<DocumentNode> nodes, RetrievalToolContext context, int maxItems, int maxExcerptChars,
            List<String> degraded, long started) {
        return fromNodeGroups(action, sourceNodeId,
                List.of(new NodeGroup(EvidenceOrigin.REFERENCE, nodes)), context,
                maxItems, maxExcerptChars, degraded, started, false);
    }

    static RetrievalObservation fromNodeGroups(RetrievalActionName action, long sourceNodeId,
            List<NodeGroup> groups, RetrievalToolContext context, int maxItems, int maxExcerptChars,
            List<String> degraded, long started) {
        return fromNodeGroups(action, sourceNodeId, groups, context, maxItems, maxExcerptChars,
                degraded, started, true);
    }

    private static RetrievalObservation fromNodeGroups(RetrievalActionName action, long sourceNodeId,
            List<NodeGroup> groups, RetrievalToolContext context, int maxItems, int maxExcerptChars,
            List<String> degraded, long started, boolean requireSameSourceVersion) {
        RetrievalToolContext.ObservedSource source = context.sourceForNode(sourceNodeId);
        List<RetrievalObservationItem> items = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (NodeGroup group : groups == null ? List.<NodeGroup>of() : groups) {
            if (group == null || group.origin() == null) continue;
            for (DocumentNode node : group.nodes() == null ? List.<DocumentNode>of() : group.nodes()) {
                if (node == null || items.size() >= maxItems || !seen.add(node.id())) continue;
                ensureSameDataset(context, node);
                if (requireSameSourceVersion) ensureSameObservedSource(context, sourceNodeId, node);
                RetrievalToolContext.ObservedSource nodeSource = context.sourceForNode(node.id());
                RetrievalToolContext.ObservedSource evidenceSource = sameSource(source, node)
                        ? source : nodeSource;
                items.add(new RetrievalObservationItem(null, node.id(), node.documentId(), node.documentVersionId(),
                        evidenceSource == null ? null : evidenceSource.indexBuildId(), node.title(), node.nodeType(),
                        group.origin(), evidenceSource == null ? 0 : evidenceSource.score(),
                        excerpt(node.content(), maxExcerptChars),
                        node.pageFrom(), node.pageTo()));
                evidence.add(structural(node, evidenceSource, group.origin(), maxExcerptChars));
            }
        }
        return new RetrievalObservation(action, "返回 " + items.size() + " 个结构节点",
                items, evidence, degraded, elapsed(started));
    }

    static void ensureSameDataset(RetrievalToolContext context, DocumentNode node) {
        if (node.datasetId() != context.datasetId()) {
            throw new IllegalArgumentException("navigation target belongs to another dataset");
        }
        RetrievalToolContext.ObservedSource source = context.sourceForNode(node.id());
        if (source != null && (source.documentId() != node.documentId()
                || source.documentVersionId() != node.documentVersionId())) {
            throw new IllegalArgumentException("navigation target is stale or outside the observed version");
        }
    }

    static void ensureSameObservedSource(RetrievalToolContext context, long sourceNodeId, DocumentNode node) {
        RetrievalToolContext.ObservedSource source = context.sourceForNode(sourceNodeId);
        if (source != null && (source.documentId() != node.documentId()
                || source.documentVersionId() != node.documentVersionId())) {
            throw new IllegalArgumentException("navigation target is outside the observed document version");
        }
    }

    private static boolean sameSource(RetrievalToolContext.ObservedSource source, DocumentNode node) {
        return source != null && source.documentId() == node.documentId()
                && source.documentVersionId() == node.documentVersionId();
    }

    static Evidence bounded(Evidence value, int maxExcerptChars) {
        return new Evidence(value.evidenceId(), value.datasetId(), value.documentId(), value.documentVersionId(),
                value.nodeId(), value.retrievalUnitId(), value.indexBuildId(), value.documentName(), value.origin(),
                value.unitType(), value.nodeType(), value.titlePath(), excerpt(value.content(), maxExcerptChars),
                value.locator(), value.score(), value.channel(), value.primary(), value.metadata());
    }

    static RetrievalObservationItem item(Evidence value, int maxExcerptChars) {
        return new RetrievalObservationItem(value.retrievalUnitId(), value.nodeId(), value.documentId(),
                value.documentVersionId(), value.indexBuildId(), value.titlePath(), value.nodeType(), value.origin(),
                value.score(), excerpt(value.content(), maxExcerptChars), value.locator().pageFrom(),
                value.locator().pageTo());
    }

    private static Evidence structural(DocumentNode node, RetrievalToolContext.ObservedSource source,
            EvidenceOrigin origin, int maxExcerptChars) {
        String documentName = source == null || source.documentName() == null || source.documentName().isBlank()
                ? "文档" : source.documentName();
        String titlePath = node.title() == null ? "" : node.title();
        return new Evidence("agent-" + origin.name().toLowerCase() + "-" + node.id(), node.datasetId(),
                node.documentId(), node.documentVersionId(), node.id(), null,
                source == null ? null : source.indexBuildId(), documentName, origin, null, node.nodeType(),
                titlePath, excerpt(node.content(), maxExcerptChars),
                new EvidenceLocator(titlePath, node.pageFrom(), node.pageTo(), node.charStart(), node.charEnd()),
                source == null ? 0 : source.score(), null, false, node.metadata());
    }

    static String excerpt(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        int max = Math.max(1, Math.min(RetrievalObservationItem.MAX_EXCERPT_CHARS, maxLength));
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    record NodeGroup(EvidenceOrigin origin, List<DocumentNode> nodes) { }
}
