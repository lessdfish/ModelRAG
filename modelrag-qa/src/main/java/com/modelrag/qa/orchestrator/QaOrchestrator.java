package com.modelrag.qa.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.event.QaAnsweredEvent;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.api.ConversationContextBuilder;
import com.modelrag.api.ConversationRepository;
import com.modelrag.common.rate.DatasetRateLimiter;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.qa.dto.Citation;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.sanitizer.ContextSanitizer;
import com.modelrag.qa.sanitizer.ContextWindowManager;
import com.modelrag.qa.sanitizer.PromptSanitizer;
import com.modelrag.qa.trace.QaTraceStore;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class QaOrchestrator {
    private static final Pattern FAQ = Pattern.compile("问[:：]\\s*([^？?\\n]+)[？?]\\s*答[:：]\\s*([^。！？\\n]+)");

    private final KnowledgeStore store;
    private final PromptSanitizer sanitizer;
    private final ContextSanitizer contextSanitizer;
    private final ContextWindowManager contextWindow;
    private final MeterRegistry metrics;
    private final TokenUsageTracker tokens;
    private final DatasetRateLimiter limiter;
    private final QaTraceStore traceStore;
    private final ApplicationEventPublisher events;
    private final ConversationRepository conversationRepository;
    private final ContextAssembler contextAssembler;
    private final RetrievalPipeline retrievalPipeline;
    private final AnswerApplicationService answerApplication;
    private final AnswerTraceRepository answerTraceRepository;
    private final int contextMaxTokens;
    private final ObjectMapper json = new ObjectMapper();

    public QaOrchestrator(
            KnowledgeStore store,
            PromptSanitizer p,
            ContextSanitizer contextSanitizer,
            ContextWindowManager contextWindow,
            MeterRegistry m,
            TokenUsageTracker t,
            DatasetRateLimiter l,
            QaTraceStore traceStore,
            ApplicationEventPublisher e,
            ConversationRepository conversationRepository,
            ContextAssembler contextAssembler,
            RetrievalPipeline retrievalPipeline,
            AnswerApplicationService answerApplication,
            AnswerTraceRepository answerTraceRepository,
            @Value("${modelrag.qa.context-max-tokens:1600}") int contextMaxTokens) {
        this.store = store;
        sanitizer = p;
        this.contextSanitizer = contextSanitizer;
        this.contextWindow = contextWindow;
        metrics = m;
        tokens = t;
        limiter = l;
        this.traceStore = traceStore;
        events = e;
        this.conversationRepository = conversationRepository;
        this.contextAssembler = contextAssembler;
        this.retrievalPipeline = retrievalPipeline;
        this.answerApplication = answerApplication;
        this.answerTraceRepository = answerTraceRepository;
        this.contextMaxTokens = contextMaxTokens;
    }

    public QaResult answer(QaRequest request) {
        return answer(request, null);
    }

    public QaResult answer(QaRequest request, Consumer<String> tokenConsumer) {
        if (request.query() == null || request.query().isBlank()) throw new IllegalArgumentException("问题不能为空");
        limiter.check(request.datasetId());
        Dataset dataset = store.dataset(request.datasetId());
        boolean userMessagePersisted = !request.persistConversationMessage() || persistUserMessage(request);
        QaResult result = answerUncached(request, tokenConsumer);
        audit(request, result);
        metrics.counter("modelrag.qa.requests", "status", result.refused() ? "refused" : "success").increment();
        tokens.record(request.datasetId(), request.query(), result.answer());
        if (request.conversationId() != null) {
            events.publishEvent(new QaAnsweredEvent(request.conversationId(), request.query(), result.answer(),
                    citationsJson(result.citations()), request.userId(), result.traceId(), "rag", dataset.name(),
                    userMessagePersisted, request.datasetId()));
        }
        return result;
    }

    private boolean persistUserMessage(QaRequest request) {
        if (!request.persistConversationMessage() || request.conversationId() == null) return false;
        conversationRepository.append(request.userId(), request.conversationId(), "user", request.query());
        return true;
    }

    private QaResult answerUncached(QaRequest request, Consumer<String> tokenConsumer) {
        long started = System.nanoTime();
        Dataset dataset = store.dataset(request.datasetId());
        String query = sanitizer.sanitize(request.query());
        ContextAssembler.ContextBundle contextBundle = contextAssembler.build(request, query);
        ConversationContextBuilder.ConversationContext conversationContext = contextBundle.conversation();
        String retrievalQuery = contextBundle.standaloneQuestion();
        if (isDatasetOverviewQuery(query)) return datasetOverview(request, dataset, query, started);
        int topK = Math.max(1, dataset.topK());
        RetrievalPipeline.RetrievalResult retrievalResult = retrievalPipeline.retrieve(
                request.datasetId(), retrievalQuery, topK, dataset.threshold());
        SearchStages stages = retrievalResult.stages();
        List<ScoredChunk> results = retrievalResult.selected();
        List<ScoredChunk> contextChunks = retrievalResult.contextChunks();
        String traceId = UUID.randomUUID().toString();
        double rawVectorScore = stages.vectorResults().isEmpty() ? 0 : stages.vectorResults().get(0).score();
        double confidence = Math.max(0, Math.min(1, rawVectorScore));
        int evidenceScore = results.stream().mapToInt(result -> relevance(query, result.content())).max().orElse(0);
        boolean strongFactEvidence = evidenceScore >= 2
                && ((asksQuantity(query) && results.stream().anyMatch(result -> containsQuantity(result.content())))
                || results.stream().anyMatch(result -> containsDecisionEvidence(query, result.content())));
        boolean anchoredFactEvidence = evidenceScore >= 1 && hasFactAnchor(query, results);
        boolean thresholdFailed = !strongFactEvidence
                && evidenceScore < 4
                && !stages.vectorResults().isEmpty()
                && rawVectorScore >= 0
                && rawVectorScore <= 1
                && confidence < dataset.threshold();
        boolean refused = results.isEmpty() || thresholdFailed || (evidenceScore < 2 && !anchoredFactEvidence);
        List<Citation> citations = refused ? List.of() : citations(request.datasetId(), results, 2);
        AnswerApplicationService.AnswerDraft draft = answerApplication.answer(request.userId(), query,
                compactEvidence(query, results), refused, conversationContext, contextChunks, tokenConsumer);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", traceId);
        trace.put("datasetId", request.datasetId());
        trace.put("query", request.query());
        trace.put("rewrittenQuery", stages.rewrittenQuery());
        trace.put("standaloneQuestion", retrievalQuery);
        trace.put("searchQueries", stages.searchQueries());
        trace.put("rerankQuery", stages.rerankQuery());
        trace.put("vectorResults", traceResults(stages.vectorResults()));
        trace.put("bm25Results", traceResults(stages.bm25Results()));
        trace.put("fusedResults", traceResults(stages.fusedResults()));
        trace.put("rerankResults", traceResults(stages.rerankResults()));
        trace.put("rerankApplied", stages.rerankApplied());
        List<String> degradedComponents = answerDegradation(stages.degradedComponents(), draft.answerSource());
        trace.put("degradedComponents", degradedComponents);
        trace.put("retrievalLatencyMs", stages.latencyMs());
        trace.put("abVariants", "[]");
        trace.put("mmrResults", traceResults(results));
        trace.put("smallToBigContext", traceResults(contextChunks));
        trace.put("contextChunks", traceResults(contextChunks));
        trace.put("finalPrompt", traceDigest("prompt", draft.finalPrompt()));
        trace.put("promptContext", traceDigest("context", draft.promptContext()));
        trace.put("contextMaxTokens", contextMaxTokens);
        trace.put("answerSource", draft.answerSource());
        trace.put("modelOutput", traceDigest("model-output", draft.modelOutput()));
        trace.put("confidence", confidence);
        trace.put("refused", refused);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        trace.put("latencyMs", latencyMs);
        persistTrace(trace);
        metrics.timer("modelrag.qa.latency", "phase", "total")
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        return new QaResult(draft.answer(), citations, confidence, refused, traceId, degradedComponents);
    }

    private List<String> answerDegradation(List<String> retrieval, String answerSource) {
        LinkedHashSet<String> values = new LinkedHashSet<>(retrieval == null ? List.of() : retrieval);
        if (answerSource != null && answerSource.startsWith("local-fallback")) values.add("user-model-failed");
        else if ("local-evidence-no-user-model".equals(answerSource)) values.add("user-model-not-configured");
        else if ("local-evidence".equals(answerSource)) values.add("model-generation-skipped");
        return List.copyOf(values);
    }

    private QaResult datasetOverview(QaRequest request, Dataset dataset, String query, long started) {
        List<com.modelrag.knowledge.model.Document> documents = store.documents(request.datasetId());
        List<Chunk> chunks = store.chunks(request.datasetId());
        List<ScoredChunk> contextChunks = chunks.stream()
                .limit(5)
                .map(chunk -> new ScoredChunk(chunk.id(), chunk.content(), 1, "dataset-overview", chunk.index() + 1))
                .toList();
        String traceId = UUID.randomUUID().toString();
        List<Citation> citations = citations(request.datasetId(), contextChunks, 2);
        String answer = overviewAnswer(dataset, documents, chunks);
        SearchStages emptyStages = new SearchStages(query, List.of(query), query, List.of(), List.of(), contextChunks, List.of(), false, contextChunks);
        AnswerApplicationService.AnswerDraft draft = new AnswerApplicationService.AnswerDraft(answer, "",
                contextWindow.fit(contextChunks.stream().map(ScoredChunk::content).toList(), contextMaxTokens),
                "dataset-overview", "");
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", traceId);
        trace.put("datasetId", request.datasetId());
        trace.put("query", request.query());
        trace.put("rewrittenQuery", query);
        trace.put("searchQueries", List.of(query));
        trace.put("rerankQuery", query);
        trace.put("vectorResults", List.of());
        trace.put("bm25Results", List.of());
        trace.put("fusedResults", traceResults(contextChunks));
        trace.put("rerankResults", List.of());
        trace.put("rerankApplied", false);
        trace.put("degradedComponents", List.of());
        trace.put("retrievalLatencyMs", Map.of());
        trace.put("abVariants", "[]");
        trace.put("mmrResults", traceResults(contextChunks));
        trace.put("smallToBigContext", traceResults(contextChunks));
        trace.put("contextChunks", traceResults(contextChunks));
        trace.put("finalPrompt", "");
        trace.put("promptContext", traceDigest("context", draft.promptContext()));
        trace.put("contextMaxTokens", contextMaxTokens);
        trace.put("answerSource", draft.answerSource());
        trace.put("modelOutput", "");
        trace.put("confidence", documents.isEmpty() ? .3 : .9);
        trace.put("refused", false);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        trace.put("latencyMs", latencyMs);
        persistTrace(trace);
        return new QaResult(answer, citations, documents.isEmpty() ? .3 : .9, false, traceId, List.of());
    }

    private boolean isDatasetOverviewQuery(String query) {
        String text = query == null ? "" : query.replaceAll("\\s+", "");
        return text.contains("当前知识库") || text.contains("这个知识库") || text.contains("知识库有什么")
                || text.contains("知识库有哪些") || text.contains("有什么内容") || text.contains("有哪些内容")
                || text.contains("文档类型") || text.contains("有哪些文件") || text.contains("有什么文件");
    }

    private List<Map<String, Object>> traceResults(List<ScoredChunk> values) {
        if (values == null) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).limit(50).map(value -> Map.<String, Object>of(
                "chunkId", value.chunkId(),
                "score", value.score(),
                "channel", value.channel() == null ? "" : value.channel(),
                "rank", value.rank(),
                "contentHash", sha256(value.content() == null ? "" : value.content()),
                "contentChars", value.content() == null ? 0 : value.content().length())).toList();
    }

    private String traceDigest(String kind, String value) {
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

    public List<Map<String, Object>> traces() {
        return answerTraceRepository.traces();
    }

    public Map<String, Object> replayTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("traceId 不能为空");
        return answerTraceRepository.replay(traceId);
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
        return answerTraceRepository.audits();
    }

    private void audit(QaRequest request, QaResult result) {
        recordAudit(request, result.answer(), citationsJson(result.citations()), result.confidence(), result.refused(), result.traceId(), "rag");
    }

    public void recordAudit(QaRequest request, String answer, String citations, double confidence, boolean refused, String traceId, String mode) {
        Map<String, Object> audit = auditMap(traceId, request.datasetId(), null, request.conversationId(), request.userId(), mode,
                request.query(), answer, citations, confidence, refused,
                java.time.Instant.now().toString());
        answerTraceRepository.saveAudit(audit);
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

    private void persistTrace(Map<String, Object> trace) {
        answerTraceRepository.saveTrace(trace);
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
                int score = relevance(query, matcher.group(1)) * 2
                        + answerSpecificity(query, matcher.group(2));
                if (score > faqScore) {
                    faqScore = score;
                    bestFaq = matcher.group(2).trim();
                }
            }
            for (String sentence : content.split("(?<=[。！？])|\\n+")) {
                String value = sentence.trim();
                if (value.isBlank() || value.startsWith("问：") || value.startsWith("问:") || headingOnly(value)) continue;
                int score = relevance(query, value) + answerSpecificity(query, value);
                if (score > sentenceScore) {
                    sentenceScore = score;
                    bestSentence = value;
                }
            }
        }
        // A related FAQ is useful only when it is at least as specific as the best policy sentence.
        // Otherwise broad FAQs (for example "年假有几天") can incorrectly answer a focused question
        // such as "年假应提前多久提交".
        String answer = bestFaq != null && faqScore >= sentenceScore ? bestFaq : bestSentence;
        if (answer != null && answer.contains("答：")) answer = answer.substring(answer.indexOf("答：") + 2).trim();
        if (answer != null && answer.contains("答:")) answer = answer.substring(answer.indexOf("答:") + 2).trim();
        if (answer != null) answer = answer.replaceFirst("^答[:：]\\s*", "");
        if (answer == null || answer.isBlank()) answer = results.isEmpty() ? "当前知识库没有足够证据回答该问题。" : results.get(0).content();
        return "结论：" + limit(answer, 240);
    }

    private int answerSpecificity(String query, String answer) {
        String question = query == null ? "" : query;
        String value = answer == null ? "" : answer;
        int score = 0;
        if (question.contains("谁")) {
            if (value.contains("只能由") || value.contains("仅由")) score += 10;
            else if (value.contains("由") || value.contains("负责") || value.contains("审批") || value.contains("确认")) score += 4;
            if (value.contains("不得") || value.contains("禁止") || value.contains("不可以")) score -= 6;
        }
        if (question.contains("首先") || question.contains("第一步")) {
            if (value.contains("立即")) score += 4;
            if (value.contains("停止") || value.contains("报告") || value.contains("保留")) score += 2;
            if (value.contains("不得") && !value.contains("立即")) score -= 2;
        }
        if (question.contains("时段") && value.matches(".*\\d{1,2}:?\\d{0,2}\\s*至\\s*\\d{1,2}:?\\d{0,2}.*")) score += 10;
        return score;
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

    /** A single distinctive term plus a directly matching time, number or decision is sufficient evidence. */
    private boolean hasFactAnchor(String query, List<ScoredChunk> results) {
        String question = query == null ? "" : query;
        boolean temporal = asksQuantity(question) || question.contains("时段") || question.contains("时限")
                || question.contains("多久") || question.contains("何时") || question.contains("几时");
        if (temporal && results.stream().anyMatch(result -> containsQuantity(result.content()))) return true;
        boolean decision = question.contains("谁") || question.contains("确认") || question.contains("审批")
                || question.contains("负责人") || question.contains("主管");
        return decision && results.stream().anyMatch(result -> containsDecisionEvidence(question, result.content()));
    }

    private boolean containsDecisionEvidence(String query, String value) {
        String question = query == null ? "" : query;
        if (!(question.contains("谁") || question.contains("确认") || question.contains("审批")
                || question.contains("负责人") || question.contains("主管"))) return false;
        String evidence = value == null ? "" : value;
        return evidence.contains("确认") || evidence.contains("审批")
                || evidence.contains("负责人") || evidence.contains("主管");
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private String json(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String citationsJson(List<Citation> citations) {
        return citations.stream()
                .map(c -> "{\"chunkId\":" + c.chunkId()
                        + ",\"documentId\":" + c.documentId()
                        + ",\"documentName\":\"" + json(c.documentName()) + "\""
                        + ",\"location\":\"" + json(c.location()) + "\""
                        + ",\"indexVersion\":" + c.indexVersion()
                        + ",\"excerpt\":\"" + json(c.excerpt()) + "\""
                        + ",\"score\":" + c.score() + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private List<Citation> citations(long datasetId, List<ScoredChunk> scored, int limit) {
        Map<Long, Chunk> chunks = store.chunks(datasetId).stream()
                .collect(Collectors.toMap(Chunk::id, chunk -> chunk, (left, right) -> left));
        return scored.stream().limit(limit).map(item -> {
            Chunk chunk = chunks.get(item.chunkId());
            if (chunk == null) return new Citation(item.chunkId(), excerpt(item.content()), item.score());
            com.modelrag.knowledge.model.Document document = store.document(chunk.documentId());
            String page = chunk.metadata().get("page");
            String title = chunk.metadata().get("titlePath");
            String location = page == null || page.isBlank()
                    ? (title == null ? "" : title)
                    : "第 " + page + " 页" + (title == null || title.isBlank() ? "" : " · " + title);
            long version = intValue(chunk.metadata().get("version"), 1);
            return new Citation(chunk.id(), chunk.documentId(), document.fileName(), location, version,
                    excerpt(item.content()), item.score());
        }).toList();
    }
}
