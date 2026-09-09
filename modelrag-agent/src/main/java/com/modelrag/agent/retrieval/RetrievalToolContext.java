package com.modelrag.agent.retrieval;

import com.modelrag.qa.evidence.Evidence;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Execution-local capability and budget state for agentic retrieval. */
public final class RetrievalToolContext {
    private final String executionId;
    private final String userId;
    private final long datasetId;
    private final Long conversationId;
    private final String originalQuery;
    private final Set<Long> observedNodeIds = new LinkedHashSet<>();
    private final Set<Long> observedDocumentIds = new LinkedHashSet<>();
    private final Map<Long, ObservedSource> sources = new LinkedHashMap<>();
    private final int initialSteps;
    private final int initialSearchActions;
    private final int initialNavigationActions;
    private int remainingSteps;
    private int remainingSearchActions;
    private int remainingNavigationActions;

    public RetrievalToolContext(String executionId, String userId, long datasetId, Long conversationId,
            String originalQuery, int remainingSteps, int remainingSearchActions,
            int remainingNavigationActions) {
        if (executionId == null || executionId.isBlank() || datasetId <= 0
                || originalQuery == null || originalQuery.isBlank()) {
            throw new IllegalArgumentException("retrieval context identity is invalid");
        }
        if (remainingSteps < 0 || remainingSearchActions < 0 || remainingNavigationActions < 0) {
            throw new IllegalArgumentException("retrieval context budget is invalid");
        }
        this.executionId = executionId;
        this.userId = userId == null ? "" : userId;
        this.datasetId = datasetId;
        this.conversationId = conversationId;
        this.originalQuery = originalQuery;
        this.initialSteps = remainingSteps;
        this.initialSearchActions = remainingSearchActions;
        this.initialNavigationActions = remainingNavigationActions;
        this.remainingSteps = remainingSteps;
        this.remainingSearchActions = remainingSearchActions;
        this.remainingNavigationActions = remainingNavigationActions;
    }

    public RetrievalToolContext(String executionId, String userId, long datasetId, Long conversationId,
            String originalQuery, Set<Long> observedNodeIds, Set<Long> observedDocumentIds,
            int remainingSteps, int remainingNavigationActions) {
        this(executionId, userId, datasetId, conversationId, originalQuery, remainingSteps,
                remainingSteps, remainingNavigationActions);
        addIds(this.observedNodeIds, observedNodeIds);
        addIds(this.observedDocumentIds, observedDocumentIds);
    }

    /** Rebuilds the capability map from the version-aware sources stored in a checkpoint. */
    public RetrievalToolContext(String executionId, String userId, long datasetId, Long conversationId,
            String originalQuery, Collection<ObservedSource> sources, int remainingSteps,
            int remainingSearchActions, int remainingNavigationActions) {
        this(executionId, userId, datasetId, conversationId, originalQuery, remainingSteps,
                remainingSearchActions, remainingNavigationActions);
        if (sources != null) sources.forEach(this::restore);
    }

    public String executionId() { return executionId; }
    public String userId() { return userId; }
    public long datasetId() { return datasetId; }
    public Long conversationId() { return conversationId; }
    public String originalQuery() { return originalQuery; }
    public int remainingSteps() { return remainingSteps; }
    public int remainingSearchActions() { return remainingSearchActions; }
    public int remainingNavigationActions() { return remainingNavigationActions; }
    public int consumedSteps() { return initialSteps - remainingSteps; }
    public int consumedSearchActions() { return initialSearchActions - remainingSearchActions; }
    public int consumedNavigationActions() { return initialNavigationActions - remainingNavigationActions; }

    public Set<Long> observedNodeIds() { return Collections.unmodifiableSet(observedNodeIds); }
    public Set<Long> observedDocumentIds() { return Collections.unmodifiableSet(observedDocumentIds); }

    public boolean consumeStep() {
        if (remainingSteps <= 0) return false;
        remainingSteps--;
        return true;
    }

    public boolean consumeSearchAction() {
        if (remainingSearchActions <= 0) return false;
        remainingSearchActions--;
        return true;
    }

    public boolean consumeNavigationAction() {
        if (remainingNavigationActions <= 0) return false;
        remainingNavigationActions--;
        return true;
    }

    public boolean hasObservedNode(long nodeId) { return observedNodeIds.contains(nodeId); }
    public boolean hasObservedDocument(long documentId) { return observedDocumentIds.contains(documentId); }

    public void requireObservedNode(long nodeId) {
        if (!hasObservedNode(nodeId)) {
            throw new IllegalArgumentException("nodeId was not observed by this retrieval execution");
        }
    }

    public void requireObservedDocument(long documentId) {
        if (!hasObservedDocument(documentId)) {
            throw new IllegalArgumentException("documentId was not observed by this retrieval execution");
        }
    }

    public ObservedSource sourceForNode(long nodeId) {
        return sources.get(nodeId);
    }

    public Collection<ObservedSource> sources() { return Collections.unmodifiableCollection(sources.values()); }

    public void restore(ObservedSource source) {
        if (source == null || source.nodeId() <= 0 || source.documentId() <= 0
                || source.documentVersionId() <= 0) return;
        observedNodeIds.add(source.nodeId());
        observedDocumentIds.add(source.documentId());
        sources.putIfAbsent(source.nodeId(), source);
    }

    /** Records only bounded observation metadata and source capabilities. */
    public void observe(RetrievalObservation observation) {
        if (observation == null) return;
        for (RetrievalObservationItem item : observation.items()) {
            observe(item);
        }
        for (Evidence evidence : observation.newEvidence()) {
            observe(evidence);
        }
    }

    public void observe(Evidence evidence) {
        if (evidence == null || evidence.datasetId() != datasetId) return;
        observedNodeIds.add(evidence.nodeId());
        observedDocumentIds.add(evidence.documentId());
        sources.putIfAbsent(evidence.nodeId(), new ObservedSource(evidence.nodeId(), evidence.documentId(),
                evidence.documentVersionId(), evidence.indexBuildId(), evidence.documentName(),
                evidence.titlePath(), evidence.nodeType(), evidence.score()));
    }

    public void observe(RetrievalObservationItem item) {
        if (item == null) return;
        if (item.documentId() <= 0 || item.nodeId() <= 0) return;
        observedNodeIds.add(item.nodeId());
        observedDocumentIds.add(item.documentId());
        sources.putIfAbsent(item.nodeId(), new ObservedSource(item.nodeId(), item.documentId(), item.documentVersionId(),
                item.indexBuildId(), "", item.titlePath(), item.nodeType(), item.score()));
    }

    private void addIds(Set<Long> target, Collection<Long> values) {
        if (values == null) return;
        for (Long value : values) if (value != null && value > 0) target.add(value);
    }

    public record ObservedSource(long nodeId, long documentId, long documentVersionId, Long indexBuildId,
            String documentName, String titlePath, com.modelrag.knowledge.model.NodeType nodeType,
            double score) {
        /** Compatibility constructor for the pre-G8 document-scoped source shape. */
        public ObservedSource(long documentId, long documentVersionId, Long indexBuildId,
                String documentName, String titlePath, com.modelrag.knowledge.model.NodeType nodeType,
                double score) {
            this(0, documentId, documentVersionId, indexBuildId, documentName, titlePath, nodeType, score);
        }
    }
}
