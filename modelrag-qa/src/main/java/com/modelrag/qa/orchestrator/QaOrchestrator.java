package com.modelrag.qa.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.cache.QaAnswerCache;
import com.modelrag.common.event.QaAnsweredEvent;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelGateway;
import com.modelrag.common.rate.DatasetRateLimiter;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.qa.ab.OnlineExperimentService;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.sanitizer.ContextSanitizer;
import com.modelrag.qa.sanitizer.ContextWindowManager;
import com.modelrag.qa.sanitizer.OutputGuard;
import com.modelrag.qa.sanitizer.PromptSanitizer;
import com.modelrag.qa.sanitizer.StructuredPromptBuilder;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QaOrchestrator {
    private static final Pattern FAQ = Pattern.compile("问[:：]\\s*([^？?\\n]+)[？?]\\s*答[:：]\\s*([^。！？\\n]+)");
    private static final double MMR_LAMBDA = 0.75;

    private final SearchFacade search;
    private final KnowledgeStore store;
    private final PromptSanitizer sanitizer;
    private final ContextSanitizer contextSanitizer;
    private final ContextWindowManager contextWindow;
    private final OutputGuard outputGuard;
    private final StructuredPromptBuilder prompts;
    private final QaAnswerCache cache;
    private final MeterRegistry metrics;
    private final TokenUsageTracker tokens;
    private final DatasetRateLimiter limiter;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<ModelGateway> models;
    private final ObjectProvider<OnlineExperimentService> experiments;
    private final boolean ollamaEnabled;
    private final int contextMaxTokens;
    private final boolean onlineAbEnabled;
    private final int onlineAbTopK;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Map<String, Object>> traces = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> audits = new CopyOnWriteArrayList<>();

    public QaOrchestrator(
            SearchFacade s,
            KnowledgeStore store,
            PromptSanitizer p,
            ContextSanitizer contextSanitizer,
            ContextWindowManager contextWindow,
            OutputGuard outputGuard,
            StructuredPromptBuilder prompts,
            QaAnswerCache c,
            MeterRegistry m,
            TokenUsageTracker t,
            DatasetRateLimiter l,
            ObjectProvider<JdbcTemplate> j,
            ApplicationEventPublisher e,
            ObjectProvider<ModelGateway> models,
            ObjectProvider<OnlineExperimentService> experiments,
            @Value("${modelrag.ollama.enabled:true}") boolean ollamaEnabled,
            @Value("${modelrag.qa.context-max-tokens:1600}") int contextMaxTokens,
            @Value("${modelrag.qa.online-ab-enabled:false}") boolean onlineAbEnabled,
            @Value("${modelrag.qa.online-ab-top-k:8}") int onlineAbTopK) {
        search = s;
        this.store = store;
        sanitizer = p;
        this.contextSanitizer = contextSanitizer;
        this.contextWindow = contextWindow;
        this.outputGuard = outputGuard;
        this.prompts = prompts;
        cache = c;
        metrics = m;
        tokens = t;
        limiter = l;
        jdbc = j;
        events = e;
        this.models = models;
        this.experiments = experiments;
        this.ollamaEnabled = ollamaEnabled;
        this.contextMaxTokens = contextMaxTokens;
        this.onlineAbEnabled = onlineAbEnabled;
        this.onlineAbTopK = Math.max(1, Math.min(20, onlineAbTopK));
    }

    public QaResult answer(QaRequest request) {
        if (request.query() == null || request.query().isBlank()) throw new IllegalArgumentException("问题不能为空");
        limiter.check(request.datasetId());
        Dataset dataset = store.dataset(request.datasetId());
        QaAnswerCache.Lookup<QaResult> lookup = cache.getWithStatus(request.datasetId() + ":rev" + dataset.revision() + ":v8:" + request.query(), QaResult.class, ignored -> answerUncached(request));
        QaResult result = lookup.hit() ? cacheHitTrace(request, lookup.value()) : lookup.value();
        audit(request, result);
        metrics.counter("modelrag.qa.requests", "status", result.refused() ? "refused" : "success").increment();
        tokens.record(request.datasetId(), request.query(), result.answer());
        if (request.conversationId() != null) {
            events.publishEvent(new QaAnsweredEvent(request.conversationId(), request.query(), result.answer(), citationsJson(result.citations()), request.userId(), result.traceId(), "rag", dataset.name()));
        }
        return result;
    }

    private QaResult cacheHitTrace(QaRequest request, QaResult cached) {
        if (cached.traceId() == null || cached.traceId().isBlank()) return cached;
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> original = replayTrace(cached.traceId());
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", traceId);
        trace.put("datasetId", request.datasetId());
        trace.put("query", request.query());
        trace.put("rewrittenQuery", original.getOrDefault("rewrittenQuery", request.query()));
        trace.put("searchQueries", original.getOrDefault("searchQueries", List.of(request.query())));
        trace.put("rerankQuery", original.getOrDefault("rerankQuery", request.query()));
        trace.put("vectorResults", original.getOrDefault("vectorResults", List.of()));
        trace.put("bm25Results", original.getOrDefault("bm25Results", List.of()));
        trace.put("fusedResults", original.getOrDefault("fusedResults", List.of()));
        trace.put("rerankResults", original.getOrDefault("rerankResults", List.of()));
        trace.put("rerankApplied", original.getOrDefault("rerankApplied", false));
        trace.put("mmrResults", original.getOrDefault("mmrResults", List.of()));
        trace.put("smallToBigContext", original.getOrDefault("smallToBigContext", original.getOrDefault("contextChunks", List.of())));
        trace.put("contextChunks", original.getOrDefault("contextChunks", List.of()));
        trace.put("abVariants", original.getOrDefault("abVariants", List.of()));
        trace.put("finalPrompt", original.getOrDefault("finalPrompt", ""));
        trace.put("promptContext", original.getOrDefault("promptContext", ""));
        trace.put("contextMaxTokens", original.getOrDefault("contextMaxTokens", contextMaxTokens));
        trace.put("answerSource", original.getOrDefault("answerSource", "cache"));
        trace.put("modelOutput", original.getOrDefault("modelOutput", ""));
        trace.put("confidence", cached.confidence());
        trace.put("refused", cached.refused());
        trace.put("cacheHit", true);
        traces.add(trace);
        persistCachedTrace(traceId, request, original, cached.confidence(), cached.refused());
        return new QaResult(cached.answer(), cached.citations(), cached.confidence(), cached.refused(), traceId);
    }

    private QaResult answerUncached(QaRequest request) {
        long started = System.nanoTime();
        Dataset dataset = store.dataset(request.datasetId());
        String query = sanitizer.sanitize(request.query());
        if (isDatasetOverviewQuery(query)) return datasetOverview(request, dataset, query, started);
        int topK = Math.max(1, dataset.topK());
        int candidateTopK = Math.max(topK * 2, 6);
        SearchStages stages = search.inspect(new HybridSearchRequest(request.datasetId(), query, candidateTopK, dataset.threshold()));
        List<ScoredChunk> results = mmr(stages.finalResults(), topK);
        List<ScoredChunk> contextChunks = expandContext(request.datasetId(), results);
        List<Map<String, Object>> abVariants = abVariants(request.datasetId(), query, topK, stages.fusedResults(), results);
        String traceId = UUID.randomUUID().toString();
        double rawVectorScore = stages.vectorResults().isEmpty() ? 0 : stages.vectorResults().get(0).score();
        double confidence = Math.max(0, Math.min(1, rawVectorScore));
        int evidenceScore = results.stream().mapToInt(result -> relevance(query, result.content())).max().orElse(0);
        boolean strongFactEvidence = evidenceScore >= 2
                && asksQuantity(query)
                && results.stream().anyMatch(result -> containsQuantity(result.content()));
        boolean thresholdFailed = !strongFactEvidence
                && evidenceScore < 4
                && !stages.vectorResults().isEmpty()
                && rawVectorScore >= 0
                && rawVectorScore <= 1
                && confidence < dataset.threshold();
        boolean refused = results.isEmpty() || thresholdFailed || evidenceScore < 2;
        List<Citation> citations = refused ? List.of() : results.stream().limit(2)
                .map(c -> new Citation(c.chunkId(), excerpt(c.content()), c.score()))
                .toList();
        AnswerDraft draft = answer(query, results, contextChunks, refused);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", traceId);
        trace.put("datasetId", request.datasetId());
        trace.put("query", request.query());
        trace.put("rewrittenQuery", stages.rewrittenQuery());
        trace.put("searchQueries", stages.searchQueries());
        trace.put("rerankQuery", stages.rerankQuery());
        trace.put("vectorResults", stages.vectorResults());
        trace.put("bm25Results", stages.bm25Results());
        trace.put("fusedResults", stages.fusedResults());
        trace.put("rerankResults", stages.rerankResults());
        trace.put("rerankApplied", stages.rerankApplied());
        trace.put("mmrResults", results);
        trace.put("smallToBigContext", contextChunks);
        trace.put("contextChunks", contextChunks);
        trace.put("abVariants", abVariants);
        trace.put("finalPrompt", draft.finalPrompt());
        trace.put("promptContext", draft.promptContext());
        trace.put("contextMaxTokens", contextMaxTokens);
        trace.put("answerSource", draft.answerSource());
        trace.put("modelOutput", draft.modelOutput());
        trace.put("confidence", confidence);
        trace.put("refused", refused);
        traces.add(trace);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        persistTrace(traceId, request, stages, results, contextChunks, abVariants, draft, confidence, refused, latencyMs);
        recordAbEvents(traceId, request.datasetId(), abVariants, refused, confidence, latencyMs);
        metrics.timer("modelrag.qa.latency", "phase", "total")
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        return new QaResult(draft.answer(), citations, confidence, refused, traceId);
    }

    private QaResult datasetOverview(QaRequest request, Dataset dataset, String query, long started) {
        List<com.modelrag.knowledge.model.Document> documents = store.documents(request.datasetId());
        List<Chunk> chunks = store.chunks(request.datasetId());
        List<ScoredChunk> contextChunks = chunks.stream()
                .limit(5)
                .map(chunk -> new ScoredChunk(chunk.id(), chunk.content(), 1, "dataset-overview", chunk.index() + 1))
                .toList();
        String traceId = UUID.randomUUID().toString();
        List<Citation> citations = contextChunks.stream().limit(2)
                .map(chunk -> new Citation(chunk.chunkId(), excerpt(chunk.content()), chunk.score()))
                .toList();
        String answer = overviewAnswer(dataset, documents, chunks);
        SearchStages emptyStages = new SearchStages(query, List.of(query), query, List.of(), List.of(), contextChunks, List.of(), false, contextChunks);
        AnswerDraft draft = new AnswerDraft(answer, "", contextWindow.fit(contextChunks.stream().map(ScoredChunk::content).toList(), contextMaxTokens), "dataset-overview", "");
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", traceId);
        trace.put("datasetId", request.datasetId());
        trace.put("query", request.query());
        trace.put("rewrittenQuery", query);
        trace.put("searchQueries", List.of(query));
        trace.put("rerankQuery", query);
        trace.put("vectorResults", List.of());
        trace.put("bm25Results", List.of());
        trace.put("fusedResults", contextChunks);
        trace.put("rerankResults", List.of());
        trace.put("rerankApplied", false);
        trace.put("mmrResults", contextChunks);
        trace.put("smallToBigContext", contextChunks);
        trace.put("contextChunks", contextChunks);
        trace.put("abVariants", List.of());
        trace.put("finalPrompt", "");
        trace.put("promptContext", draft.promptContext());
        trace.put("contextMaxTokens", contextMaxTokens);
        trace.put("answerSource", draft.answerSource());
        trace.put("modelOutput", "");
        trace.put("confidence", documents.isEmpty() ? .3 : .9);
        trace.put("refused", false);
        traces.add(trace);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        persistTrace(traceId, request, emptyStages, contextChunks, contextChunks, List.of(), draft, documents.isEmpty() ? .3 : .9, false, latencyMs);
        return new QaResult(answer, citations, documents.isEmpty() ? .3 : .9, false, traceId);
    }

    private boolean isDatasetOverviewQuery(String query) {
        String text = query == null ? "" : query.replaceAll("\\s+", "");
        return text.contains("当前知识库") || text.contains("这个知识库") || text.contains("知识库有什么")
                || text.contains("知识库有哪些") || text.contains("有什么内容") || text.contains("有哪些内容")
                || text.contains("文档类型") || text.contains("有哪些文件") || text.contains("有什么文件");
    }

    private String overviewAnswer(Dataset dataset, List<com.modelrag.knowledge.model.Document> documents, List<Chunk> chunks) {
        if (documents.isEmpty()) return "当前知识库“" + dataset.name() + "”还没有上传文档。";
        Map<String, Long> types = documents.stream().collect(Collectors.groupingBy(
                document -> document.fileType() == null || document.fileType().isBlank() ? fileSuffix(document.fileName()) : document.fileType(),
                LinkedHashMap::new,
                Collectors.counting()));
        String files = documents.stream()
                .limit(8)
                .map(document -> document.fileName() + "（" + document.chunkCount() + " 个分块）")
                .collect(Collectors.joining("；"));
        String snippets = chunks.stream()
                .map(Chunk::content)
                .map(contextSanitizer::sanitize)
                .map(value -> limit(value, 90))
                .distinct()
                .limit(3)
                .collect(Collectors.joining(" / "));
        StringBuilder answer = new StringBuilder();
        answer.append("当前知识库“").append(dataset.name()).append("”包含 ")
                .append(documents.size()).append(" 份文档、").append(chunks.size()).append(" 个可检索分块。");
        answer.append("文件类型：").append(types.entrySet().stream()
                .map(entry -> entry.getKey() + " × " + entry.getValue())
                .collect(Collectors.joining("，"))).append("。");
        answer.append("主要文件：").append(files).append("。");
        if (!snippets.isBlank()) answer.append("内容概览：").append(snippets).append("。");
        return answer.toString();
    }

    private String fileSuffix(String fileName) {
        String value = fileName == null ? "" : fileName;
        int index = value.lastIndexOf('.');
        return index >= 0 && index + 1 < value.length() ? value.substring(index + 1).toLowerCase() : "unknown";
    }

    private record AnswerDraft(String answer, String finalPrompt, String promptContext, String answerSource, String modelOutput) {}

    private AnswerDraft answer(String query, List<ScoredChunk> results, List<ScoredChunk> contextChunks, boolean refused) {
        String fallback = refused
                ? "当前知识库没有足够证据回答该问题。请补充文档或换一种表述。"
                : compactEvidence(query, results);
        String context = contextWindow.fit(contextChunks.stream()
                .map(ScoredChunk::content)
                .map(contextSanitizer::sanitize)
                .toList(), contextMaxTokens);
        String prompt = prompts.build(query, context);
        if (refused) return new AnswerDraft(fallback, prompt, context, "refusal", "");
        if (extractiveQuery(query)) return new AnswerDraft(fallback, prompt, context, "local-evidence", "");
        if (!ollamaEnabled) return new AnswerDraft(fallback, prompt, context, "local-evidence", "");
        try {
            ModelGateway gateway = models.getIfAvailable();
            if (gateway == null) return new AnswerDraft(fallback, prompt, context, "local-evidence", "");
            String generated = gateway.generate(prompt).trim();
            if (generated.isBlank() || generated.startsWith("[mock]") || generated.startsWith("[fallback]") || !outputGuard.safe(generated)) {
                return new AnswerDraft(fallback, prompt, context, "local-evidence", generated);
            }
            return new AnswerDraft(compactGenerated(generated), prompt, context, "llm", generated);
        } catch (RuntimeException ignored) {
            return new AnswerDraft(fallback, prompt, context, "local-fallback", "");
        }
    }

    public List<Map<String, Object>> traces() {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            return db.query("SELECT trace_id,dataset_id,query_original,query_rewritten,search_queries::text,rerank_query,vector_results,bm25_results,fused_results,rerank_results,rerank_applied,mmr_results::text,small_to_big_context::text,context_chunks,ab_variants::text,final_prompt,prompt_context,context_max_tokens,answer_source,model_output,confidence,refused,latency_ms FROM kb_retrieval_trace WHERE trace_id IS NOT NULL ORDER BY id DESC LIMIT 100",
                    (rs, n) -> traceMap(rs.getString("trace_id"), rs.getLong("dataset_id"), rs.getString("query_original"),
                            rs.getString("query_rewritten"), rs.getString("search_queries"), rs.getString("rerank_query"),
                            rs.getString("vector_results"), rs.getString("bm25_results"), rs.getString("fused_results"),
                            rs.getString("rerank_results"), rs.getBoolean("rerank_applied"), rs.getString("mmr_results"),
                            rs.getString("small_to_big_context"), rs.getString("context_chunks"),
                            rs.getString("ab_variants"), rs.getString("final_prompt"), rs.getString("prompt_context"),
                            rs.getInt("context_max_tokens"), rs.getString("answer_source"), rs.getString("model_output"),
                            rs.getDouble("confidence"), rs.getBoolean("refused"), rs.getLong("latency_ms")));
        } catch (Exception ignored) {
        }
        return List.copyOf(traces);
    }

    public Map<String, Object> replayTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("traceId 不能为空");
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            List<Map<String, Object>> rows = db.query("""
                    SELECT t.trace_id,t.dataset_id,t.query_original,t.query_rewritten,t.search_queries::text,t.rerank_query,
                           t.vector_results::text,t.bm25_results::text,
                           t.fused_results::text,t.rerank_results::text,t.rerank_applied,
                           t.mmr_results::text,t.small_to_big_context::text,t.context_chunks::text,t.ab_variants::text,
                           t.final_prompt,t.prompt_context,t.context_max_tokens,t.answer_source,t.model_output,
                           t.confidence,t.refused,t.latency_ms,a.answer,a.citations::text
                    FROM kb_retrieval_trace t
                    LEFT JOIN kb_qa_audit a ON a.trace_id=t.trace_id
                    WHERE t.trace_id=?
                    ORDER BY a.id DESC NULLS LAST
                    LIMIT 1
                    """, (rs, n) -> replayMap(
                            rs.getString("trace_id"), rs.getLong("dataset_id"), rs.getString("query_original"),
                            rs.getString("query_rewritten"), rs.getString("search_queries"), rs.getString("rerank_query"),
                            rs.getString("vector_results"), rs.getString("bm25_results"), rs.getString("fused_results"),
                            rs.getString("rerank_results"), rs.getBoolean("rerank_applied"), rs.getString("mmr_results"),
                            rs.getString("small_to_big_context"), rs.getString("context_chunks"),
                            rs.getString("ab_variants"), rs.getString("final_prompt"), rs.getString("prompt_context"),
                            rs.getInt("context_max_tokens"), rs.getString("answer_source"), rs.getString("model_output"),
                            rs.getDouble("confidence"), rs.getBoolean("refused"), rs.getLong("latency_ms"),
                            rs.getString("answer"), rs.getString("citations")), traceId);
            if (!rows.isEmpty()) return rows.get(0);
        } catch (Exception ignored) {
        }
        return traces.stream()
                .filter(trace -> traceId.equals(trace.get("traceId")))
                .findFirst()
                .map(trace -> {
                    Map<String, Object> result = new LinkedHashMap<>(trace);
                    result.put("found", true);
                    result.put("answer", audits.stream()
                            .filter(audit -> traceId.equals(audit.get("traceId")))
                            .map(audit -> audit.get("answer"))
                            .findFirst()
                            .orElse(null));
                    Object vector = trace.getOrDefault("vectorResults", List.of());
                    Object bm25 = trace.getOrDefault("bm25Results", List.of());
                    Object fused = trace.getOrDefault("fusedResults", List.of());
                    Object context = trace.getOrDefault("contextChunks", List.of());
                    ReplayDiagnosis diagnosis = diagnose(
                            vector,
                            bm25,
                            fused,
                            context,
                            Boolean.TRUE.equals(trace.get("refused")),
                            String.valueOf(result.getOrDefault("answer", "")));
                    result.put("stageCounts", Map.of(
                            "vector", arrayCount(vector),
                            "bm25", arrayCount(bm25),
                            "fused", arrayCount(fused),
                            "rerank", arrayCount(trace.getOrDefault("rerankResults", List.of())),
                            "mmr", arrayCount(trace.getOrDefault("mmrResults", List.of())),
                            "smallToBig", arrayCount(trace.getOrDefault("smallToBigContext", context)),
                            "context", arrayCount(context)));
                    result.put("diagnosis", diagnosis.messages());
                    result.put("failureStage", diagnosis.failureStage());
                    result.put("actionHints", diagnosis.actionHints());
                    result.put("evidencePreview", evidencePreview(context));
                    return result;
                })
                .orElseGet(() -> Map.of("traceId", traceId, "found", false));
    }

    private Map<String, Object> traceMap(
            String traceId, long datasetId, String query, String rewrittenQuery, String searchQueries, String rerankQuery, String vector, String bm25, String fused, String rerank,
            boolean rerankApplied, String context, String abVariants, String finalPrompt, String promptContext, int contextMaxTokens,
            String answerSource, String modelOutput, double confidence, boolean refused, long latency) {
        return traceMap(traceId, datasetId, query, rewrittenQuery, searchQueries, rerankQuery, vector, bm25, fused, rerank,
                rerankApplied, "[]", context, context, abVariants, finalPrompt, promptContext, contextMaxTokens,
                answerSource, modelOutput, confidence, refused, latency);
    }

    private Map<String, Object> traceMap(
            String traceId, long datasetId, String query, String rewrittenQuery, String searchQueries, String rerankQuery, String vector, String bm25, String fused, String rerank,
            boolean rerankApplied, String mmr, String smallToBig, String context, String abVariants, String finalPrompt, String promptContext, int contextMaxTokens,
            String answerSource, String modelOutput, double confidence, boolean refused, long latency) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("datasetId", datasetId);
        result.put("query", query);
        result.put("rewrittenQuery", rewrittenQuery);
        result.put("searchQueries", searchQueries);
        result.put("rerankQuery", rerankQuery);
        result.put("vectorResults", vector);
        result.put("bm25Results", bm25);
        result.put("fusedResults", fused);
        result.put("rerankResults", rerank);
        result.put("rerankApplied", rerankApplied);
        result.put("mmrResults", mmr == null ? "[]" : mmr);
        result.put("smallToBigContext", smallToBig == null ? context : smallToBig);
        result.put("contextChunks", context);
        result.put("abVariants", abVariants == null ? "[]" : abVariants);
        result.put("finalPrompt", finalPrompt);
        result.put("promptContext", promptContext);
        result.put("contextMaxTokens", contextMaxTokens);
        result.put("answerSource", answerSource);
        result.put("modelOutput", modelOutput);
        result.put("confidence", confidence);
        result.put("refused", refused);
        result.put("latencyMs", latency);
        return result;
    }

    private Map<String, Object> replayMap(String traceId, long datasetId, String query, String rewrittenQuery, String searchQueries, String rerankQuery, String vector, String bm25,
            String fused, String rerank, boolean rerankApplied, String mmr, String smallToBig, String context, String abVariants,
            String finalPrompt, String promptContext, int contextMaxTokens, String answerSource, String modelOutput,
            double confidence, boolean refused, long latency, String answer, String citations) {
        Map<String, Object> result = traceMap(traceId, datasetId, query, rewrittenQuery, searchQueries, rerankQuery, vector, bm25, fused, rerank, rerankApplied,
                mmr, smallToBig, context, abVariants, finalPrompt, promptContext, contextMaxTokens, answerSource, modelOutput, confidence, refused, latency);
        result.put("found", true);
        result.put("answer", answer);
        result.put("citations", citations == null ? "[]" : citations);
        result.put("stageCounts", Map.of(
                "vector", jsonArrayCount(vector),
                "bm25", jsonArrayCount(bm25),
                "fused", jsonArrayCount(fused),
                "rerank", jsonArrayCount(rerank),
                "mmr", jsonArrayCount(mmr),
                "smallToBig", jsonArrayCount(smallToBig),
                "context", jsonArrayCount(context)));
        ReplayDiagnosis diagnosis = diagnose(vector, bm25, fused, context, refused, answer);
        result.put("diagnosis", diagnosis.messages());
        result.put("failureStage", diagnosis.failureStage());
        result.put("actionHints", diagnosis.actionHints());
        result.put("evidencePreview", evidencePreview(context));
        return result;
    }

    private record ReplayDiagnosis(String failureStage, List<String> messages, List<String> actionHints) {}

    private ReplayDiagnosis diagnose(Object vector, Object bm25, Object fused, Object context, boolean refused, String answer) {
        int vectorCount = arrayCount(vector);
        int bm25Count = arrayCount(bm25);
        int fusedCount = arrayCount(fused);
        int contextCount = arrayCount(context);
        List<String> messages = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        String stage;
        if (vectorCount == 0 && bm25Count == 0) {
            stage = "RECALL";
            messages.add("召回问题：向量和 BM25 都没有召回候选。");
            hints.add("检查知识库是否完成索引、文档是否上传到正确知识库。");
            hints.add("补充 Query 改写词或提高向量/BM25 召回 TopN。");
        } else if (fusedCount == 0) {
            stage = "FUSION_RANKING";
            messages.add("融合/排序问题：召回存在，但 RRF 融合后没有候选。");
            hints.add("检查 RRF 权重、去重逻辑和分数阈值是否过严。");
            hints.add("保留向量/BM25 原始 TopN，回放具体 chunk 是否被过滤。");
        } else if (contextCount == 0) {
            stage = "CONTEXT_SELECTION";
            messages.add("上下文选择问题：已有候选，但最终没有拼入上下文。");
            hints.add("检查 MMR、Small-to-Big 扩展和上下文 token 限制。");
            hints.add("降低证据选择过滤强度或提高知识库 topK。");
        } else if (refused) {
            stage = "REFUSAL_THRESHOLD";
            messages.add("阈值拒答问题：上下文存在，但系统拒答。");
            hints.add("检查 similarity_threshold、证据相关性打分和拒答 Prompt。");
            hints.add("如果人工确认可回答，将该样本加入评测集并调低阈值或补充同义词。");
        } else if (answer == null || answer.isBlank() || "null".equalsIgnoreCase(answer.trim())) {
            stage = "GENERATION";
            messages.add("生成问题：上下文存在，但没有留下最终回答。");
            hints.add("检查主模型调用、输出护栏和 fallback 策略。");
            hints.add("确认 Prompt 中证据格式没有被截断或污染。");
        } else {
            stage = "ANSWER_USE";
            messages.add("链路完整：召回、融合、上下文和回答均存在，bad case 优先检查答案是否使用了证据。");
            hints.add("对照最终上下文原文核查答案是否忠实。");
            hints.add("将该问题作为 bad-case 入评测集，标注期望 chunk 和期望答案。");
        }
        if (vectorCount == 0 && bm25Count > 0) messages.add("向量召回偏弱：本次主要依赖 BM25。");
        if (bm25Count == 0 && vectorCount > 0) messages.add("关键词召回偏弱：本次主要依赖向量。");
        return new ReplayDiagnosis(stage, messages, hints);
    }

    private int arrayCount(Object value) {
        if (value instanceof Iterable<?> items) {
            int count = 0;
            for (Object ignored : items) count++;
            return count;
        }
        return jsonArrayCount(String.valueOf(value));
    }

    private int jsonArrayCount(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return 0;
        int count = 0;
        for (int i = 0; i < json.length(); i++) if (json.charAt(i) == '{') count++;
        return count;
    }

    private List<Map<String, Object>> evidencePreview(Object context) {
        if (context instanceof Iterable<?> items) {
            List<Map<String, Object>> result = new ArrayList<>();
            int index = 0;
            for (Object item : items) {
                if (index++ >= 5) break;
                Map<String, Object> row = previewRow(item);
                if (!row.isEmpty()) result.add(row);
            }
            return result;
        }
        String raw = String.valueOf(context == null ? "[]" : context);
        if (raw.isBlank() || "[]".equals(raw.trim())) return List.of();
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode nodes = this.json.readTree(raw);
            if (!nodes.isArray()) return List.of();
            int index = 0;
            for (com.fasterxml.jackson.databind.JsonNode node : nodes) {
                if (index++ >= 5) break;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chunkId", node.path("chunkId").isMissingNode() ? null : node.path("chunkId").asLong());
                row.put("rank", node.path("rank").isMissingNode() ? index : node.path("rank").asInt());
                row.put("score", node.path("score").isMissingNode() ? 0 : node.path("score").asDouble());
                row.put("channel", node.path("channel").asText(""));
                row.put("excerpt", limit(contextSanitizer.sanitize(node.path("content").asText("")), 180));
                result.add(row);
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> previewRow(Object item) {
        if (item instanceof ScoredChunk chunk) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", chunk.chunkId());
            row.put("rank", chunk.rank());
            row.put("score", chunk.score());
            row.put("channel", chunk.channel());
            row.put("excerpt", limit(contextSanitizer.sanitize(chunk.content()), 180));
            return row;
        }
        if (item instanceof Map<?, ?> map) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", map.get("chunkId"));
            row.put("rank", map.get("rank"));
            row.put("score", map.get("score"));
            Object channel = map.containsKey("channel") ? map.get("channel") : "";
            Object content = map.containsKey("content") ? map.get("content") : "";
            row.put("channel", channel);
            row.put("excerpt", limit(contextSanitizer.sanitize(String.valueOf(content)), 180));
            return row;
        }
        return Map.of();
    }

    public List<Map<String, Object>> audits() {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            return db.query("SELECT a.trace_id,a.dataset_id,d.name AS dataset_name,a.conversation_id,a.user_id,a.mode,a.query,a.answer,a.citations,a.confidence,a.refused,a.create_time FROM kb_qa_audit a LEFT JOIN kb_dataset d ON d.id=a.dataset_id ORDER BY a.id DESC LIMIT 200",
                    (rs, n) -> auditMap(rs.getString("trace_id"), rs.getLong("dataset_id"), rs.getString("dataset_name"),
                            rs.getObject("conversation_id", Long.class), rs.getString("user_id"), rs.getString("mode"), rs.getString("query"), rs.getString("answer"),
                            rs.getString("citations"), rs.getDouble("confidence"), rs.getBoolean("refused"),
                            rs.getTimestamp("create_time").toInstant().toString()));
        } catch (Exception ignored) {
        }
        return List.copyOf(audits);
    }

    private void audit(QaRequest request, QaResult result) {
        recordAudit(request, result.answer(), citationsJson(result.citations()), result.confidence(), result.refused(), result.traceId(), "rag");
    }

    public void recordAudit(QaRequest request, String answer, String citations, double confidence, boolean refused, String traceId, String mode) {
        Map<String, Object> audit = auditMap(traceId, request.datasetId(), null, request.conversationId(), request.userId(), mode,
                request.query(), answer, citations, confidence, refused,
                java.time.Instant.now().toString());
        audits.add(audit);
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("INSERT INTO kb_qa_audit(trace_id,dataset_id,conversation_id,user_id,mode,query,answer,citations,confidence,refused) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)",
                    traceId, request.datasetId(), request.conversationId(), request.userId(), mode, request.query(), answer,
                    citations, confidence, refused);
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> auditMap(String traceId, long datasetId, String datasetName, Long conversationId, String userId, String mode,
            String query, String answer, String citations, double confidence, boolean refused, String createdAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("datasetId", datasetId);
        result.put("datasetName", datasetName);
        result.put("conversationId", conversationId);
        result.put("userId", userId);
        result.put("mode", mode);
        result.put("query", query);
        result.put("answer", answer);
        result.put("citations", citations);
        result.put("confidence", confidence);
        result.put("refused", refused);
        result.put("createdAt", createdAt);
        return result;
    }

    private void persistTrace(String traceId, QaRequest request, SearchStages stages, List<ScoredChunk> mmrResults, List<ScoredChunk> contextChunks, List<Map<String, Object>> abVariants,
            AnswerDraft draft, double confidence, boolean refused, long latency) {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("INSERT INTO kb_retrieval_trace(trace_id,dataset_id,query_original,query_rewritten,search_queries,rerank_query,vector_results,bm25_results,fused_results,rerank_results,rerank_applied,mmr_results,small_to_big_context,context_chunks,ab_variants,final_prompt,prompt_context,context_max_tokens,answer_source,model_output,confidence,refused,latency_ms) VALUES (?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,?,?,?,?,?,?,?)",
                    traceId, request.datasetId(), request.query(), stages.rewrittenQuery(), stringsJson(stages.searchQueries()), stages.rerankQuery(), resultsJson(stages.vectorResults()),
                    resultsJson(stages.bm25Results()), resultsJson(stages.fusedResults()), resultsJson(stages.rerankResults()),
                    stages.rerankApplied(), resultsJson(mmrResults), contextJson(contextChunks), contextJson(contextChunks), abVariantsJson(abVariants), draft.finalPrompt(), draft.promptContext(),
                    contextMaxTokens, draft.answerSource(), draft.modelOutput(), confidence, refused, latency);
        } catch (Exception ignored) {
        }
    }

    private void persistCachedTrace(String traceId, QaRequest request, Map<String, Object> original, double confidence, boolean refused) {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("INSERT INTO kb_retrieval_trace(trace_id,dataset_id,query_original,query_rewritten,search_queries,rerank_query,vector_results,bm25_results,fused_results,rerank_results,rerank_applied,mmr_results,small_to_big_context,context_chunks,ab_variants,final_prompt,prompt_context,context_max_tokens,answer_source,model_output,confidence,refused,latency_ms) VALUES (?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,?,?,?,?,?,?,?)",
                    traceId, request.datasetId(), request.query(),
                    stringValue(original.get("rewrittenQuery"), request.query()),
                    jsonArray(original.get("searchQueries"), stringsJson(List.of(request.query()))),
                    stringValue(original.get("rerankQuery"), request.query()),
                    jsonArray(original.get("vectorResults"), "[]"),
                    jsonArray(original.get("bm25Results"), "[]"),
                    jsonArray(original.get("fusedResults"), "[]"),
                    jsonArray(original.get("rerankResults"), "[]"),
                    Boolean.TRUE.equals(original.get("rerankApplied")),
                    jsonArray(original.get("mmrResults"), "[]"),
                    jsonArray(original.get("smallToBigContext"), jsonArray(original.get("contextChunks"), "[]")),
                    jsonArray(original.get("contextChunks"), "[]"),
                    jsonArray(original.get("abVariants"), "[]"),
                    stringValue(original.get("finalPrompt"), ""),
                    stringValue(original.get("promptContext"), ""),
                    intValue(original.get("contextMaxTokens"), contextMaxTokens),
                    stringValue(original.get("answerSource"), "cache"),
                    stringValue(original.get("modelOutput"), ""),
                    confidence, refused, 0);
        } catch (Exception ignored) {
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String stringValue(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() || "null".equalsIgnoreCase(text) ? fallback : text;
    }

    private String jsonArray(Object value, String fallback) {
        if (value == null) return fallback;
        if (value instanceof String text) return text.trim().startsWith("[") ? text : fallback;
        if (value instanceof List<?> list && list.stream().allMatch(ScoredChunk.class::isInstance)) {
            @SuppressWarnings("unchecked")
            List<ScoredChunk> chunks = (List<ScoredChunk>) list;
            return resultsJson(chunks);
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return stringsJson(list.stream().map(String.class::cast).toList());
        }
        return fallback;
    }

    private List<Map<String, Object>> abVariants(long datasetId, String query, int currentTopK, List<ScoredChunk> baselineFused, List<ScoredChunk> baselineFinal) {
        OnlineExperimentService service = experiments == null ? null : experiments.getIfAvailable();
        if (service != null) {
            List<Map<String, Object>> configured = new ArrayList<>();
            for (OnlineExperimentService.Experiment experiment : service.active(datasetId, query)) {
                if (experiment.variantTopK() == currentTopK) continue;
                configured.add(abVariant(datasetId, query, currentTopK, baselineFused, baselineFinal,
                        experiment.id(), "online-ab-v1", "topK-" + experiment.variantTopK(), experiment.variantTopK()));
            }
            if (!configured.isEmpty()) return configured;
        }
        if (!onlineAbEnabled || onlineAbTopK == currentTopK) return List.of();
        return List.of(abVariant(datasetId, query, currentTopK, baselineFused, baselineFinal,
                "online-topk-shadow", "qa-online-ab-v1", "shadow-topK-" + onlineAbTopK, onlineAbTopK));
    }

    private Map<String, Object> abVariant(long datasetId, String query, int currentTopK, List<ScoredChunk> baselineFused,
            List<ScoredChunk> baselineFinal, String experimentId, String policyVersion, String variantName, int topK) {
        try {
            Dataset dataset = store.dataset(datasetId);
            SearchStages shadow = search.inspect(new HybridSearchRequest(datasetId, query, topK, dataset.threshold()));
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("experimentId", experimentId);
            variant.put("policyVersion", policyVersion);
            variant.put("variant", variantName);
            variant.put("baselineTopK", currentTopK);
            variant.put("topK", topK);
            variant.put("baselineFusedChunkIds", ids(baselineFused));
            variant.put("baselineFinalChunkIds", ids(baselineFinal));
            variant.put("fusedChunkIds", ids(shadow.fusedResults()));
            variant.put("finalChunkIds", ids(shadow.finalResults()));
            variant.put("deltaFinalChunkIds", difference(ids(shadow.finalResults()), ids(baselineFinal)));
            variant.put("rerankApplied", shadow.rerankApplied());
            return variant;
        } catch (RuntimeException ignored) {
            return Map.of("experimentId", experimentId, "policyVersion", policyVersion,
                    "variant", variantName, "baselineTopK", currentTopK, "topK", topK,
                    "baselineFinalChunkIds", ids(baselineFinal), "error", "shadow-search-failed");
        }
    }

    private void recordAbEvents(String traceId, long datasetId, List<Map<String, Object>> variants, boolean refused, double confidence, long latencyMs) {
        OnlineExperimentService service = experiments == null ? null : experiments.getIfAvailable();
        if (service == null) return;
        for (Map<String, Object> variant : variants) {
            service.record(traceId, datasetId, variant, refused, confidence, latencyMs);
        }
    }

    private List<Long> ids(List<ScoredChunk> chunks) {
        return chunks.stream().map(ScoredChunk::chunkId).toList();
    }

    private List<Long> difference(List<Long> left, List<Long> right) {
        Set<Long> seen = new HashSet<>(right);
        return left.stream().filter(id -> !seen.contains(id)).toList();
    }

    private List<ScoredChunk> mmr(List<ScoredChunk> candidates, int limit) {
        List<ScoredChunk> remaining = new ArrayList<>(candidates);
        List<ScoredChunk> selected = new ArrayList<>();
        double maxScore = remaining.stream().mapToDouble(ScoredChunk::score).max().orElse(1);
        while (!remaining.isEmpty() && selected.size() < limit) {
            ScoredChunk best = remaining.stream()
                    .max(Comparator.comparingDouble(candidate -> mmrScore(candidate, selected, maxScore)))
                    .orElseThrow();
            selected.add(best);
            remaining.remove(best);
        }
        return selected;
    }

    private double mmrScore(ScoredChunk candidate, List<ScoredChunk> selected, double maxScore) {
        double relevance = maxScore <= 0 ? 0 : candidate.score() / maxScore;
        double redundancy = selected.stream().mapToDouble(item -> jaccard(candidate.content(), item.content())).max().orElse(0);
        return MMR_LAMBDA * relevance - (1 - MMR_LAMBDA) * redundancy;
    }

    private double jaccard(String left, String right) {
        Set<String> a = pairs(left);
        Set<String> b = pairs(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return (double) both.size() / all.size();
    }

    private Set<String> pairs(String value) {
        String normalized = value.replaceAll("[\\s，。！？、：:；;（）()]+", "");
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) result.add(normalized.substring(i, i + 2));
        return result;
    }

    private List<ScoredChunk> expandContext(long datasetId, List<ScoredChunk> selected) {
        List<Chunk> all = store.chunks(datasetId);
        Map<Long, Chunk> byId = all.stream().collect(Collectors.toMap(Chunk::id, chunk -> chunk, (a, b) -> a));
        Map<String, Chunk> byPosition = all.stream().collect(Collectors.toMap(
                chunk -> chunk.documentId() + ":" + chunk.index(), chunk -> chunk, (a, b) -> a));
        List<ScoredChunk> expanded = new ArrayList<>();
        for (ScoredChunk scored : selected) {
            Chunk center = byId.get(scored.chunkId());
            if (center == null) {
                expanded.add(scored);
                continue;
            }
            if (center.parentChunkId() != null) {
                String parentContent = all.stream()
                        .filter(chunk -> center.parentChunkId().equals(chunk.parentChunkId()))
                        .filter(chunk -> chunk.documentId() == center.documentId())
                        .sorted(Comparator.comparingInt(Chunk::index))
                        .map(Chunk::content)
                        .collect(Collectors.joining("\n"));
                if (!parentContent.isBlank()) {
                    expanded.add(new ScoredChunk(scored.chunkId(), parentContent, scored.score(), scored.channel(), scored.rank()));
                    continue;
                }
            }
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            for (int offset = -1; offset <= 1; offset++) {
                Chunk adjacent = byPosition.get(center.documentId() + ":" + (center.index() + offset));
                if (adjacent != null) ids.add(adjacent.id());
            }
            String content = ids.stream()
                    .map(byId::get)
                    .filter(chunk -> chunk != null)
                    .map(Chunk::content)
                    .collect(Collectors.joining("\n"));
            expanded.add(new ScoredChunk(scored.chunkId(), content, scored.score(), scored.channel(), scored.rank()));
        }
        return expanded;
    }

    private String excerpt(String value) {
        String clean = contextSanitizer.sanitize(value);
        return clean.substring(0, Math.min(160, clean.length()));
    }

    private String compactEvidence(String query, List<ScoredChunk> results) {
        String bestFaq = null;
        String bestSentence = null;
        int faqScore = 0;
        int sentenceScore = 0;
        for (ScoredChunk chunk : results) {
            String content = contextSanitizer.sanitize(chunk.content());
            if (extractiveQuery(query)) {
                String lead = leadEvidence(query, content);
                if (!lead.isBlank()) return "结论：" + limit(lead, 360);
            }
            Matcher matcher = FAQ.matcher(content);
            while (matcher.find()) {
                int score = relevance(query, matcher.group(1));
                if (score > faqScore) {
                    faqScore = score;
                    bestFaq = matcher.group(2).trim();
                }
            }
            for (String sentence : content.split("(?<=[。！？])|\\n+")) {
                String value = sentence.trim();
                if (value.isBlank() || value.startsWith("问：") || value.startsWith("问:") || headingOnly(value)) continue;
                int score = relevance(query, value);
                if (score > sentenceScore) {
                    sentenceScore = score;
                    bestSentence = value;
                }
            }
        }
        String answer = bestFaq != null && faqScore > 0 ? bestFaq : bestSentence;
        if (answer != null && answer.contains("答：")) answer = answer.substring(answer.indexOf("答：") + 2).trim();
        if (answer != null && answer.contains("答:")) answer = answer.substring(answer.indexOf("答:") + 2).trim();
        if (answer != null) answer = answer.replaceFirst("^答[:：]\\s*", "");
        if (answer == null || answer.isBlank()) answer = results.isEmpty() ? "当前知识库没有足够证据回答该问题。" : results.get(0).content();
        return "结论：" + limit(answer, 240);
    }

    private String leadEvidence(String query, String content) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        String anchor = quotedAnchor(query);
        if (!anchor.isBlank()) {
            int index = text.indexOf(anchor);
            if (index >= 0) text = text.substring(index + anchor.length()).trim();
        }
        text = stripLeadingTopicPrefix(text);
        for (String part : text.split("[。！？]+")) {
            String sentence = stripLeadingTopicPrefix(part.trim());
            if (sentence.isBlank() || headingOnly(sentence)) continue;
            return sentence + "。";
        }
        return "";
    }

    private String quotedAnchor(String query) {
        Matcher matcher = Pattern.compile("“([^”]+)”").matcher(query == null ? "" : query);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String stripLeadingTopicPrefix(String value) {
        String text = (value == null ? "" : value).replaceFirst("^#+\\s*", "").trim();
        return text.replaceFirst("^([^。！？]{0,100}?)(本制度|本规范|本流程|安全事件是指|员工因|员工每|供应商|采购申请)", "$2").trim();
    }

    private boolean extractiveQuery(String query) {
        String value = query == null ? "" : query;
        return value.contains("文档规定") || value.contains("制度规定") || value.contains("规定了什么")
                || value.contains("要求是什么") || value.contains("有哪些要求");
    }

    private boolean headingOnly(String value) {
        String text = (value == null ? "" : value).trim().replaceFirst("^#+\\s*", "");
        return text.matches("^\\d+[\\.．、]\\s*[^。！？]{1,30}$")
                || text.matches("^第[一二三四五六七八九十]+章\\s*[^。！？]{1,30}$")
                || (text.length() <= 24 && !text.matches(".*[。！？，,；;].*"));
    }

    private int relevance(String query, String value) {
        String normalized = query.replaceAll("[\\s，。！？、：:]+", "");
        int score = 0;
        for (int i = 0; i + 1 < normalized.length(); i++) {
            String pair = normalized.substring(i, i + 2);
            if (pair.equals("的是") || pair.equals("什么") || pair.equals("多少") || pair.equals("有几")
                    || pair.equals("几天") || pair.equals("假有")) continue;
            if (value.contains(pair)) score++;
        }
        if (score == 1 && asksQuantity(query) && containsQuantity(value)) score++;
        return score;
    }

    private boolean asksQuantity(String query) {
        String value = query == null ? "" : query;
        return value.contains("几天") || value.contains("多少") || value.contains("多久") || value.contains("多长");
    }

    private boolean containsQuantity(String value) {
        String text = value == null ? "" : value;
        return text.matches(".*[0-9一二三四五六七八九十百千万]+\\s*(天|日|个工作日|小时|分钟|个月|年|次|元|%)?.*");
    }

    private String compactGenerated(String answer) {
        String clean = answer.replaceAll("(?m)^\\s*(问|问题)[:：].*$", "").trim();
        StringBuilder result = new StringBuilder();
        for (String sentence : clean.split("(?<=[。！？])|\\n+")) {
            String value = sentence.trim();
            if (value.isBlank() || value.startsWith("问：") || value.startsWith("问:")) continue;
            if (result.length() + value.length() > 360) break;
            result.append(value);
            if (result.length() >= 220) break;
        }
        return result.isEmpty() ? limit(clean, 360) : result.toString();
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private String resultsJson(List<ScoredChunk> results) {
        return results.stream()
                .map(c -> "{\"chunkId\":" + c.chunkId()
                        + ",\"rank\":" + c.rank()
                        + ",\"score\":" + c.score()
                        + ",\"channel\":\"" + json(c.channel()) + "\""
                        + ",\"content\":\"" + json(c.content()) + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String stringsJson(List<String> values) {
        return values.stream().map(value -> "\"" + json(value) + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings("unchecked")
    private String abVariantsJson(List<Map<String, Object>> variants) {
        return variants.stream().map(variant -> {
            Object finalIds = variant.getOrDefault("finalChunkIds", List.of());
            Object fusedIds = variant.getOrDefault("fusedChunkIds", List.of());
            Object baselineFinalIds = variant.getOrDefault("baselineFinalChunkIds", List.of());
            Object baselineFusedIds = variant.getOrDefault("baselineFusedChunkIds", List.of());
            Object deltaFinalIds = variant.getOrDefault("deltaFinalChunkIds", List.of());
            String error = String.valueOf(variant.getOrDefault("error", ""));
            return "{\"experimentId\":\"" + json(String.valueOf(variant.getOrDefault("experimentId", ""))) + "\""
                    + ",\"policyVersion\":\"" + json(String.valueOf(variant.getOrDefault("policyVersion", ""))) + "\""
                    + ",\"variant\":\"" + json(String.valueOf(variant.getOrDefault("variant", ""))) + "\""
                    + ",\"baselineTopK\":" + variant.getOrDefault("baselineTopK", 0)
                    + ",\"topK\":" + variant.getOrDefault("topK", 0)
                    + ",\"rerankApplied\":" + variant.getOrDefault("rerankApplied", false)
                    + ",\"baselineFusedChunkIds\":" + longListJson(baselineFusedIds instanceof List<?> list ? (List<?>) list : List.of())
                    + ",\"baselineFinalChunkIds\":" + longListJson(baselineFinalIds instanceof List<?> list ? (List<?>) list : List.of())
                    + ",\"fusedChunkIds\":" + longListJson(fusedIds instanceof List<?> list ? (List<?>) list : List.of())
                    + ",\"finalChunkIds\":" + longListJson(finalIds instanceof List<?> list ? (List<?>) list : List.of())
                    + ",\"deltaFinalChunkIds\":" + longListJson(deltaFinalIds instanceof List<?> list ? (List<?>) list : List.of())
                    + (error.isBlank() ? "" : ",\"error\":\"" + json(error) + "\"")
                    + "}";
        }).collect(Collectors.joining(",", "[", "]"));
    }

    private String longListJson(List<?> values) {
        return values.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(number -> String.valueOf(number.longValue()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String contextJson(List<ScoredChunk> results) {
        return results.stream()
                .map(c -> "{\"chunkId\":" + c.chunkId()
                        + ",\"rank\":" + c.rank()
                        + ",\"score\":" + c.score()
                        + ",\"channel\":\"" + json(c.channel()) + "\""
                        + ",\"content\":\"" + json(c.content()) + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String json(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String citationsJson(List<Citation> citations) {
        return citations.stream()
                .map(c -> "{\"chunkId\":" + c.chunkId() + ",\"score\":" + c.score() + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
