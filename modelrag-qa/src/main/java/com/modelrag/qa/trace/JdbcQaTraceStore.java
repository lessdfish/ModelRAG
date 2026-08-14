package com.modelrag.qa.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL is the source of truth for retrieval traces and QA audits. */
@Service
@Profile("!test")
public class JdbcQaTraceStore implements QaTraceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcQaTraceStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void saveTrace(Map<String, Object> trace) {
        jdbc.update("""
                INSERT INTO kb_retrieval_trace(
                    trace_id,dataset_id,query_original,query_rewritten,search_queries,rerank_query,
                    vector_results,bm25_results,fused_results,rerank_results,rerank_applied,
                    mmr_results,small_to_big_context,context_chunks,ab_variants,final_prompt,
                    prompt_context,context_max_tokens,answer_source,model_output,confidence,refused,
                    latency_ms,degraded_components,retrieval_latency_ms)
                VALUES (?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),
                    CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),
                    ?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """, value(trace, "traceId"), number(trace, "datasetId"), value(trace, "query"),
                value(trace, "rewrittenQuery"), jsonArray(trace.get("searchQueries")), value(trace, "rerankQuery"),
                jsonArray(trace.get("vectorResults")), jsonArray(trace.get("bm25Results")),
                jsonArray(trace.get("fusedResults")), jsonArray(trace.get("rerankResults")),
                Boolean.TRUE.equals(trace.get("rerankApplied")), jsonArray(trace.get("mmrResults")),
                jsonArray(trace.get("smallToBigContext")), jsonArray(trace.get("contextChunks")),
                jsonArray(trace.get("abVariants")), value(trace, "finalPrompt"), value(trace, "promptContext"),
                integer(trace, "contextMaxTokens"), value(trace, "answerSource"), value(trace, "modelOutput"),
                decimal(trace, "confidence"), Boolean.TRUE.equals(trace.get("refused")), integer(trace, "latencyMs"),
                jsonArray(trace.get("degradedComponents")), jsonObject(trace.get("retrievalLatencyMs")));
    }

    @Override
    public List<Map<String, Object>> traces() {
        return jdbc.query("""
                SELECT trace_id,dataset_id,query_original,query_rewritten,search_queries::text,
                       rerank_query,vector_results::text,bm25_results::text,fused_results::text,
                       rerank_results::text,rerank_applied,mmr_results::text,small_to_big_context::text,
                       context_chunks::text,ab_variants::text,final_prompt,prompt_context,context_max_tokens,
                       answer_source,model_output,confidence,refused,latency_ms,
                       degraded_components::text,retrieval_latency_ms::text
                FROM kb_retrieval_trace
                WHERE trace_id IS NOT NULL
                ORDER BY id DESC LIMIT 100
                """, (rs, n) -> traceMap(rs.getString("trace_id"), rs.getLong("dataset_id"),
                rs.getString("query_original"), rs.getString("query_rewritten"), rs.getString("search_queries"),
                rs.getString("rerank_query"), rs.getString("vector_results"), rs.getString("bm25_results"),
                rs.getString("fused_results"), rs.getString("rerank_results"), rs.getBoolean("rerank_applied"),
                rs.getString("mmr_results"), rs.getString("small_to_big_context"), rs.getString("context_chunks"),
                rs.getString("ab_variants"), rs.getString("final_prompt"), rs.getString("prompt_context"),
                rs.getInt("context_max_tokens"), rs.getString("answer_source"), rs.getString("model_output"),
                rs.getDouble("confidence"), rs.getBoolean("refused"), rs.getLong("latency_ms"),
                rs.getString("degraded_components"), rs.getString("retrieval_latency_ms")));
    }

    @Override
    public Map<String, Object> replay(String traceId) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT t.trace_id,t.dataset_id,t.query_original,t.query_rewritten,t.search_queries::text,
                       t.rerank_query,t.vector_results::text,t.bm25_results::text,t.fused_results::text,
                       t.rerank_results::text,t.rerank_applied,t.mmr_results::text,t.small_to_big_context::text,
                       t.context_chunks::text,t.ab_variants::text,t.final_prompt,t.prompt_context,
                       t.context_max_tokens,t.answer_source,t.model_output,t.confidence,t.refused,t.latency_ms,
                       t.degraded_components::text,t.retrieval_latency_ms::text,a.answer,a.citations::text
                FROM kb_retrieval_trace t
                LEFT JOIN kb_qa_audit a ON a.trace_id=t.trace_id
                WHERE t.trace_id=?
                ORDER BY a.id DESC NULLS LAST
                LIMIT 1
                """, (rs, n) -> {
            Map<String, Object> result = traceMap(rs.getString("trace_id"), rs.getLong("dataset_id"),
                    rs.getString("query_original"), rs.getString("query_rewritten"), rs.getString("search_queries"),
                    rs.getString("rerank_query"), rs.getString("vector_results"), rs.getString("bm25_results"),
                    rs.getString("fused_results"), rs.getString("rerank_results"), rs.getBoolean("rerank_applied"),
                    rs.getString("mmr_results"), rs.getString("small_to_big_context"), rs.getString("context_chunks"),
                    rs.getString("ab_variants"), rs.getString("final_prompt"), rs.getString("prompt_context"),
                    rs.getInt("context_max_tokens"), rs.getString("answer_source"), rs.getString("model_output"),
                    rs.getDouble("confidence"), rs.getBoolean("refused"), rs.getLong("latency_ms"),
                    rs.getString("degraded_components"), rs.getString("retrieval_latency_ms"));
            result.put("found", true);
            result.put("answer", rs.getString("answer"));
            result.put("citations", rs.getString("citations") == null ? "[]" : rs.getString("citations"));
            return result;
        }, traceId);
        return rows.isEmpty() ? Map.of("traceId", traceId, "found", false) : rows.get(0);
    }

    @Override
    public void saveAudit(Map<String, Object> audit) {
        jdbc.update("""
                INSERT INTO kb_qa_audit(trace_id,dataset_id,conversation_id,user_id,mode,query,answer,
                    citations,confidence,refused)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
                """, value(audit, "traceId"), number(audit, "datasetId"), nullableLong(audit.get("conversationId")),
                value(audit, "userId"), value(audit, "mode"), value(audit, "query"), value(audit, "answer"),
                jsonArray(audit.get("citations")), decimal(audit, "confidence"),
                Boolean.TRUE.equals(audit.get("refused")));
    }

    @Override
    public List<Map<String, Object>> audits() {
        return jdbc.query("""
                SELECT a.trace_id,a.dataset_id,d.name AS dataset_name,a.conversation_id,a.user_id,a.mode,
                       a.query,a.answer,a.citations::text,a.confidence,a.refused,a.create_time
                FROM kb_qa_audit a LEFT JOIN kb_dataset d ON d.id=a.dataset_id
                ORDER BY a.id DESC LIMIT 200
                """, (rs, n) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("traceId", rs.getString("trace_id"));
            result.put("datasetId", rs.getLong("dataset_id"));
            result.put("datasetName", rs.getString("dataset_name"));
            result.put("conversationId", rs.getObject("conversation_id", Long.class));
            result.put("userId", rs.getString("user_id"));
            result.put("mode", rs.getString("mode"));
            result.put("query", rs.getString("query"));
            result.put("answer", rs.getString("answer"));
            result.put("citations", rs.getString("citations"));
            result.put("confidence", rs.getDouble("confidence"));
            result.put("refused", rs.getBoolean("refused"));
            Timestamp created = rs.getTimestamp("create_time");
            result.put("createdAt", created == null ? null : created.toInstant().toString());
            return result;
        });
    }

    private Map<String, Object> traceMap(String traceId, long datasetId, String query, String rewrittenQuery,
            String searchQueries, String rerankQuery, String vector, String bm25, String fused, String rerank,
            boolean rerankApplied, String mmr, String smallToBig, String context, String abVariants,
            String finalPrompt, String promptContext, int contextMaxTokens, String answerSource,
            String modelOutput, double confidence, boolean refused, long latency, String degraded,
            String retrievalLatency) {
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
        result.put("degradedComponents", degraded == null ? "[]" : degraded);
        result.put("retrievalLatencyMs", retrievalLatency == null ? "{}" : retrievalLatency);
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

    private String jsonArray(Object value) {
        if (value == null) return "[]";
        if (value instanceof String text) return text.isBlank() ? "[]" : text;
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("QA trace JSON 序列化失败", error);
        }
    }

    private String jsonObject(Object value) {
        if (value == null) return "{}";
        if (value instanceof String text) return text.isBlank() ? "{}" : text;
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("QA trace JSON 序列化失败", error);
        }
    }

    private String value(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private long number(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private Integer integer(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.intValue() : value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    private Double decimal(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.doubleValue() : value == null ? null : Double.valueOf(String.valueOf(value));
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : value == null ? null : Long.valueOf(String.valueOf(value));
    }
}
