package com.modelrag.qa.orchestrator;

import com.modelrag.qa.trace.QaTraceStore;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Durable boundary for QA traces and audits; production implementation is PostgreSQL-backed. */
@Service
public class AnswerTraceRepository {
    private final QaTraceStore store;

    public AnswerTraceRepository(QaTraceStore store) { this.store = store; }

    public void saveTrace(Map<String, Object> trace) { store.saveTrace(trace); }
    public Map<String, Object> replay(String traceId) { return store.replay(traceId); }
    public List<Map<String, Object>> traces() { return store.traces(); }
    public void saveAudit(Map<String, Object> audit) { store.saveAudit(audit); }
    public List<Map<String, Object>> audits() { return store.audits(); }
}
