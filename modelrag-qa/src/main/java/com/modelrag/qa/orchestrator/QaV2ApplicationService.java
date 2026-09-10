package com.modelrag.qa.orchestrator;

import com.modelrag.api.ConversationContextBuilder.ConversationContext;
import com.modelrag.common.observability.RetrievalTraceContext;
import com.modelrag.common.observability.RetrievalTraceSession;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.common.observability.TraceCorrelation;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.evidence.AnswerSynthesizer;
import com.modelrag.qa.evidence.Evidence;
import com.modelrag.qa.evidence.EvidenceExpansionService;
import com.modelrag.qa.evidence.EvidenceRetrievalService;
import com.modelrag.qa.evidence.EvidenceSelector;
import com.modelrag.qa.evidence.EvidenceSet;
import com.modelrag.qa.evidence.EvidenceSufficiency;
import com.modelrag.qa.evidence.EvidenceSufficiencyPolicy;
import com.modelrag.qa.sanitizer.PromptSanitizer;
import com.modelrag.search.config.V2EmbeddingProfileProvider;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Opt-in V2 QA flow. It never reads legacy Chunk data or falls back to V1 evidence. */
@Service
@Profile("!test")
public class QaV2ApplicationService {
    private final DatasetRepository datasets;
    private final PromptSanitizer sanitizer;
    private final ContextAssembler contextAssembler;
    private final V2EmbeddingProfileProvider embeddingProfiles;
    private final HybridRetrievalService retrieval;
    private final EvidenceRetrievalService evidenceRetrieval;
    private final EvidenceExpansionService evidenceExpansion;
    private final EvidenceSelector evidenceSelector;
    private final EvidenceSufficiencyPolicy sufficiencyPolicy;
    private final AnswerSynthesizer synthesizer;
    private final AnswerTraceRepository traces;
    private final MeterRegistry metrics;
    private volatile RetrievalTraceSink traceSink = RetrievalTraceSink.NOOP;

    public QaV2ApplicationService(DatasetRepository datasets, PromptSanitizer sanitizer,
            ContextAssembler contextAssembler, V2EmbeddingProfileProvider embeddingProfiles,
            HybridRetrievalService retrieval, EvidenceRetrievalService evidenceRetrieval,
            EvidenceExpansionService evidenceExpansion, EvidenceSelector evidenceSelector,
            EvidenceSufficiencyPolicy sufficiencyPolicy, AnswerSynthesizer synthesizer,
            AnswerTraceRepository traces, MeterRegistry metrics) {
        this.datasets = datasets;
        this.sanitizer = sanitizer;
        this.contextAssembler = contextAssembler;
        this.embeddingProfiles = embeddingProfiles;
        this.retrieval = retrieval;
        this.evidenceRetrieval = evidenceRetrieval;
        this.evidenceExpansion = evidenceExpansion;
        this.evidenceSelector = evidenceSelector;
        this.sufficiencyPolicy = sufficiencyPolicy;
        this.synthesizer = synthesizer;
        this.traces = traces;
        this.metrics = metrics;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRetrievalTraceSink(RetrievalTraceSink traceSink) {
        this.traceSink = traceSink == null ? RetrievalTraceSink.NOOP : traceSink;
    }

    public QaResult answer(QaRequest request, Consumer<String> tokenConsumer) {
        long started = System.nanoTime();
        RetrievalTraceContext traceContext = TraceCorrelation.current();
        String traceId = traceContext == null ? UUID.randomUUID().toString() : traceContext.traceId();
        String query = sanitizer.sanitize(request.query());
        String retrievalQuery = query;
        try {
            Dataset dataset = datasets.findById(request.datasetId());
            ContextAssembler.ContextBundle contextBundle = contextAssembler.build(request, query);
            ConversationContext conversation = contextBundle.conversation();
            retrievalQuery = contextBundle.standaloneQuestion() == null
                    || contextBundle.standaloneQuestion().isBlank() ? query : contextBundle.standaloneQuestion();
            int topK = Math.min(RetrievalV2Request.MAX_TOP_K, Math.max(1, dataset.topK()));
            RetrievalV2Stages stages = retrieval.inspect(new RetrievalV2Request(request.datasetId(), retrievalQuery,
                    topK, dataset.threshold(), embeddingProfiles.profile()));
            EvidenceRetrievalService.EvidenceRetrievalResult primary = evidenceRetrieval.retrieve(
                    request.datasetId(), stages.finalCandidates());
            EvidenceExpansionService.ExpansionResult expanded = evidenceExpansion.expand(primary.primaryEvidence());
            List<Evidence> selected = evidenceSelector.select(expanded.evidence());
            EvidenceSufficiency sufficiency = sufficiencyPolicy.evaluate(query, selected);
            List<String> degraded = degraded(List.of(stages.degradedComponents(), primary.degradedComponents(),
                    expanded.degradedComponents()));
            EvidenceSet evidenceSet = new EvidenceSet(traceId, query, selected, sufficiency, degraded,
                    stages.latencyMs().getOrDefault("total", elapsed(started)), expanded.navigationActions());
            metricEvidence(evidenceSet);
            if (!sufficiency.sufficient()) {
                QaResult result = new QaResult(AnswerSynthesizer.INSUFFICIENT_EVIDENCE, List.of(),
                        sufficiency.confidence(), true, traceId, degraded);
                recordV2Evidence(selected, true, degraded, expanded.navigationActions());
                persistTraceSafely(request, traceId, retrievalQuery, stages, evidenceSet, null,
                        true, started);
                return result;
            }
            AnswerSynthesizer.AnswerDraft draft = synthesizer.synthesize(request.userId(), query, conversation,
                    evidenceSet, tokenConsumer);
            List<Citation> citations = selected.stream().filter(Evidence::primary)
                    .map(Citation::fromEvidence).toList();
            List<String> answerDegraded = answerDegraded(degraded, draft.answerSource());
            QaResult result = new QaResult(draft.answer(), citations, sufficiency.confidence(), false,
                    traceId, answerDegraded);
            recordV2Evidence(selected, false, answerDegraded, expanded.navigationActions());
            persistTraceSafely(request, traceId, retrievalQuery, stages, evidenceSet, draft,
                    false, started);
            return result;
        } catch (RuntimeException error) {
            List<String> degraded = List.of("v2-failure");
            EvidenceSufficiency sufficiency = new EvidenceSufficiency(false, 0, "v2-failure", 0);
            EvidenceSet empty = new EvidenceSet(traceId, query, List.of(), sufficiency, degraded,
                    elapsed(started), 0);
            metricEvidence(empty);
            recordV2Evidence(List.of(), true, degraded, 0);
            persistTraceSafely(request, traceId, retrievalQuery, null, empty, null, true, started);
            return new QaResult(AnswerSynthesizer.INSUFFICIENT_EVIDENCE, List.of(), 0, true,
                    traceId, degraded);
        }
    }

    private void persistTraceSafely(QaRequest request, String traceId, String retrievalQuery,
            RetrievalV2Stages stages, EvidenceSet evidenceSet, AnswerSynthesizer.AnswerDraft draft,
            boolean refused, long started) {
        try {
            persistTrace(request, traceId, retrievalQuery, stages, evidenceSet, draft, refused, started);
        } catch (RuntimeException error) {
            metrics.counter("modelrag.qa.v2.trace_persistence_failure").increment();
        }
    }

    private void metricEvidence(EvidenceSet evidenceSet) {
        metrics.counter("modelrag.qa.v2.evidence.count").increment(evidenceSet.evidence().size());
        metrics.counter("modelrag.qa.v2.evidence.primary").increment(evidenceSet.primaryEvidence().size());
        metrics.counter("modelrag.qa.v2.evidence.expanded").increment(evidenceSet.expandedEvidence().size());
        metrics.counter("modelrag.qa.v2.evidence.navigation_actions").increment(evidenceSet.navigationActions());
        metrics.summary("modelrag.qa.v2.evidence.coverage").record(evidenceSet.sufficiency().coverage());
        if (evidenceSet.sufficiency().sufficient()) metrics.counter("modelrag.qa.v2.evidence.sufficient").increment();
        else metrics.counter("modelrag.qa.v2.refused").increment();
    }

    private void recordV2Evidence(List<Evidence> selected, boolean refused, List<String> degraded,
            int navigationActions) {
        RetrievalTraceSession trace = TraceCorrelation.currentSession();
        if (trace == null) return;
        List<Evidence> values = selected == null ? List.of() : selected.stream().filter(java.util.Objects::nonNull)
                .limit(EvidenceSet.MAX_EVIDENCE).toList();
        long actionId = trace.action("EVIDENCE_CAPTURE", "v2",
                Map.of("stage", "evidence", "selectedCount", values.size(), "navigationActions", navigationActions),
                Map.of("evidenceCount", values.size(), "refused", refused), 0, values.size(),
                degraded != null && !degraded.isEmpty(), degraded);
        int rank = 0;
        for (Evidence value : values) {
            rank++;
            Map<String, Object> locator = new java.util.LinkedHashMap<>();
            locator.put("titlePath", value.titlePath());
            if (value.locator().pageFrom() != null) {
                locator.put("pageFrom", value.locator().pageFrom());
                locator.put("pageTo", value.locator().pageTo());
            }
            if (value.locator().charStart() != null) {
                locator.put("charStart", value.locator().charStart());
                locator.put("charEnd", value.locator().charEnd());
            }
            trace.evidence(actionId, new RetrievalTraceSink.Evidence(0, value.datasetId(), value.documentId(),
                    value.documentVersionId(), value.nodeId(), value.retrievalUnitId(),
                    value.channel() == null ? "unknown" : value.channel().name(), value.score(), rank, value.primary(),
                    value.content(), locator));
        }
        trace.action("ANSWER_GENERATE", "answer", Map.of("stage", "answer"),
                Map.of("refused", refused), 0, 0, false, degraded);
    }

    private List<String> answerDegraded(List<String> existing, String source) {
        LinkedHashSet<String> values = new LinkedHashSet<>(existing == null ? List.of() : existing);
        if (source != null && source.startsWith("local-fallback")) values.add("user-model-failed");
        else if ("local-evidence-no-user-model".equals(source)) values.add("user-model-not-configured");
        else if ("local-evidence".equals(source)) values.add("model-generation-skipped");
        return List.copyOf(values);
    }

    private List<String> degraded(List<List<String>> parts) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (List<String> part : parts) if (part != null) values.addAll(part);
        return List.copyOf(values);
    }

    private void persistTrace(QaRequest request, String traceId, String retrievalQuery,
            RetrievalV2Stages stages, EvidenceSet evidenceSet, AnswerSynthesizer.AnswerDraft draft,
            boolean refused, long started) {
        List<RetrievalCandidate> candidates = stages == null ? List.of() : stages.finalCandidates();
        List<Evidence> evidence = evidenceSet.evidence();
        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("traceId", traceId);
        trace.put("datasetId", request.datasetId());
        trace.put("query", request.query());
        trace.put("retrievalMode", "V2");
        trace.put("rewrittenQuery", stages == null ? retrievalQuery : stages.rewrittenQuery());
        trace.put("searchQueries", stages == null ? List.of(retrievalQuery) : stages.searchQueries());
        trace.put("retrievalCandidateIds", candidates.stream().limit(100).map(RetrievalCandidate::retrievalUnitId).toList());
        trace.put("evidenceIds", evidence.stream().map(Evidence::evidenceId).toList());
        trace.put("nodeIds", evidence.stream().map(Evidence::nodeId).distinct().limit(EvidenceSet.MAX_EVIDENCE).toList());
        trace.put("documentVersionIds", evidence.stream().map(Evidence::documentVersionId).distinct().limit(EvidenceSet.MAX_EVIDENCE).toList());
        trace.put("navigationActionCount", evidenceSet.navigationActions());
        trace.put("sufficiency", evidenceSet.sufficiency().sufficient());
        trace.put("evidenceCoverage", evidenceSet.sufficiency().coverage());
        trace.put("degradedComponents", evidenceSet.degradedComponents());
        trace.put("retrievalLatencyMs", Map.of("total", evidenceSet.retrievalLatencyMs(),
                "navigationActions", evidenceSet.navigationActions(),
                "evidenceCount", evidence.size()));
        trace.put("abVariants", Map.of("retrievalMode", "V2",
                "retrievalCandidateIds", candidates.stream().limit(100)
                        .map(RetrievalCandidate::retrievalUnitId).toList(),
                "evidenceIds", evidence.stream().map(Evidence::evidenceId).toList(),
                "nodeIds", evidence.stream().map(Evidence::nodeId).distinct().limit(EvidenceSet.MAX_EVIDENCE).toList(),
                "documentVersionIds", evidence.stream().map(Evidence::documentVersionId).distinct()
                        .limit(EvidenceSet.MAX_EVIDENCE).toList()));
        trace.put("fusedResults", candidates.stream().limit(50).map(candidate -> Map.of(
                "retrievalUnitId", candidate.retrievalUnitId(), "nodeId", candidate.nodeId(),
                "score", candidate.score(), "channel", candidate.channel().name())).toList());
        trace.put("rerankApplied", stages != null && stages.rerankApplied());
        trace.put("contextChunks", List.of());
        trace.put("smallToBigContext", List.of());
        trace.put("mmrResults", List.of());
        trace.put("finalPrompt", draft == null ? "" : digest("prompt", draft.finalPrompt()));
        trace.put("promptContext", draft == null ? "" : digest("context", draft.promptContext()));
        trace.put("answerSource", draft == null ? "refusal" : draft.answerSource());
        trace.put("modelOutput", draft == null ? "" : digest("model-output", draft.modelOutput()));
        trace.put("confidence", evidenceSet.sufficiency().confidence());
        trace.put("refused", refused);
        trace.put("latencyMs", elapsed(started));
        traces.saveTrace(trace);
    }

    private String digest(String kind, String value) {
        String text = value == null ? "" : value;
        return kind + ":sha256=" + sha256(text) + ";chars=" + text.length() + ";redacted=true";
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
