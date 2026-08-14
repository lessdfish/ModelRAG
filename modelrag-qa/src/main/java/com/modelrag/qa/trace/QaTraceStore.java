package com.modelrag.qa.trace;

import java.util.List;
import java.util.Map;

/** Durable trace/audit boundary. Production implementations must use the database. */
public interface QaTraceStore {
    void saveTrace(Map<String, Object> trace);

    List<Map<String, Object>> traces();

    Map<String, Object> replay(String traceId);

    void saveAudit(Map<String, Object> audit);

    List<Map<String, Object>> audits();
}
