package com.modelrag.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL sink for bounded retrieval actions and evidence identities. */
@Service
@Profile("!test")
public class JdbcRetrievalTraceSink implements RetrievalTraceSink {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRetrievalTraceSink(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void start(RetrievalTraceContext context, String ignoredQuerySummary) {
        jdbc.update("""
                INSERT INTO kb_retrieval_trace(
                    trace_id,request_id,dataset_id,query_original,mode,user_id,execution_id,status,profile,
                    total_actions,total_evidence,started_at)
                VALUES (?,?,?,'[redacted]',?,?,?,?,?,0,0,NOW())
                ON CONFLICT (trace_id) WHERE trace_id IS NOT NULL DO UPDATE SET
                    request_id=EXCLUDED.request_id,mode=EXCLUDED.mode,user_id=EXCLUDED.user_id,
                    execution_id=EXCLUDED.execution_id,profile=EXCLUDED.profile,status='RUNNING',
                    started_at=COALESCE(kb_retrieval_trace.started_at, EXCLUDED.started_at)
                """, context.traceId(), context.requestId(), context.datasetId(), context.mode(), context.userId(),
                context.executionId(), "RUNNING", context.profile());
    }

    @Override
    public long recordAction(RetrievalTraceContext context, Action action) {
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_retrieval_action(
                    trace_id,step_no,action_type,channel,request_summary,result_summary,latency_ms,candidate_count,
                    degraded,degraded_components)
                VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?,CAST(? AS jsonb))
                RETURNING id
                """, Long.class, context.traceId(), action.stepNo(), bounded(action.actionType(), "UNKNOWN", 40),
                bounded(action.channel(), "unknown", 20), BoundedTracePayload.json(json, action.requestSummary()),
                BoundedTracePayload.json(json, action.resultSummary()), Math.max(0, action.latencyMs()),
                Math.max(0, action.candidateCount()), action.degraded(),
                BoundedTracePayload.jsonList(json, action.degradedComponents()));
        return id == null ? action.stepNo() : id;
    }

    @Override
    public void recordEvidence(RetrievalTraceContext context, Evidence evidence) {
        jdbc.update("""
                INSERT INTO kb_retrieval_evidence(
                    trace_id,action_id,dataset_id,document_id,document_version_id,node_id,retrieval_unit_id,
                    channel,score,rank,selected,excerpt,locator)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,CAST(? AS jsonb))
                """, context.traceId(), evidence.actionId() <= 0 ? null : evidence.actionId(), evidence.datasetId(),
                evidence.documentId(), evidence.documentVersionId(), evidence.nodeId(), evidence.retrievalUnitId(),
                bounded(evidence.channel(), "unknown", 20), evidence.score(), evidence.rank(), evidence.selected(),
                BoundedTracePayload.safeExcerpt(evidence.excerpt()),
                jsonLocator(evidence.locator()));
    }

    @Override
    public void complete(RetrievalTraceContext context, Completion completion) {
        jdbc.update("""
                UPDATE kb_retrieval_trace
                SET status=?,total_actions=?,total_evidence=?,latency_ms=?,refused=?,
                    degraded_components=CAST(? AS jsonb),completed_at=NOW()
                WHERE trace_id=?
                """, completion.refused() ? "REFUSED" : "DONE", completion.totalActions(), completion.totalEvidence(),
                Math.min(Integer.MAX_VALUE, Math.max(0, completion.latencyMs())), completion.refused(),
                BoundedTracePayload.jsonList(json, completion.degradedComponents()), context.traceId());
    }

    @Override
    public void fail(RetrievalTraceContext context, String safeError, Completion completion) {
        jdbc.update("""
                UPDATE kb_retrieval_trace
                SET status='ERROR',total_actions=?,total_evidence=?,latency_ms=?,refused=TRUE,
                    degraded_components=CAST(? AS jsonb),completed_at=NOW()
                WHERE trace_id=?
                """, completion.totalActions(), completion.totalEvidence(),
                Math.min(Integer.MAX_VALUE, Math.max(0, completion.latencyMs())),
                BoundedTracePayload.jsonList(json, java.util.List.of(safeError == null ? "trace-failure" : safeError)),
                context.traceId());
    }

    private String jsonLocator(Map<String, ?> locator) {
        try {
            String text = json.writeValueAsString(BoundedTracePayload.safeLocator(locator));
            return text.length() <= BoundedTracePayload.MAX_JSON_CHARS ? text : "{}";
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String bounded(String value, String fallback, int max) {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        return result.substring(0, Math.min(max, result.length()));
    }
}
