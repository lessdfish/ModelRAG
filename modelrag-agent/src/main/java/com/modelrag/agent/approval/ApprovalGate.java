package com.modelrag.agent.approval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.modelrag.common.security.RequestUser;

@Service
public class ApprovalGate {
    private static final int TTL_SECONDS = 300;
    private final Map<String, ApprovalRecord> records = new ConcurrentHashMap<>();
    private final ObjectProvider<JdbcTemplate> jdbc;

    public ApprovalGate(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    public ApprovalRecord request(String executionId, String tool, String params) {
        return request(executionId, tool, params, null, null, null);
    }

    public ApprovalRecord request(String executionId, String tool, String params, String requesterUserId, Long datasetId, Long conversationId) {
        String id = UUID.randomUUID().toString();
        ApprovalRecord record = new ApprovalRecord(id, executionId, tool, params, "PENDING", null,
                Instant.now().plusSeconds(TTL_SECONDS), requesterUserId, datasetId, conversationId);
        records.put(id, record);
        persist(record);
        return record;
    }

    public ApprovalRecord decide(String id, boolean approved) {
        return decide(id, approved, null);
    }

    public ApprovalRecord decide(String id, boolean approved, String userId) {
        ApprovalRecord record = get(id).orElseThrow(() -> new IllegalArgumentException("审批记录不存在"));
        if (Instant.now().isAfter(record.expiresAt())) record = record.decided("TIMEOUT", userId);
        else if ("PENDING".equals(record.status())) record = record.decided(approved ? "APPROVED" : "REJECTED", userId);
        records.put(id, record);
        persist(record);
        return record;
    }

    public ApprovalRecord validateDecision(String id, String approverUserId, Long datasetId, Long conversationId) {
        ApprovalRecord record = get(id).orElseThrow(() -> new IllegalArgumentException("审批记录不存在"));
        if (record.requesterUserId() != null && record.requesterUserId().equals(approverUserId)) {
            throw new IllegalArgumentException("审批发起人不能审批自己的高风险请求");
        }
        if (record.datasetId() != null && !Objects.equals(record.datasetId(), datasetId)) {
            throw new IllegalArgumentException("审批请求所属知识库不匹配");
        }
        if (record.conversationId() != null && !Objects.equals(record.conversationId(), conversationId)) {
            throw new IllegalArgumentException("审批请求所属会话不匹配");
        }
        return record;
    }

    public Optional<ApprovalRecord> get(String id) {
        ApprovalRecord local = records.get(id);
        if (local != null) return Optional.of(local);
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db == null) return Optional.empty();
        try {
            ApprovalRecord loaded = db.query("""
                    SELECT approval_id,execution_id,tool_name,tool_params::text,status,approved_by,
                           create_time + ttl_seconds * INTERVAL '1 second' AS expires_at,
                           requester_user_id,dataset_id,conversation_id
                    FROM kb_approval_record WHERE approval_id=?
                    """, rs -> rs.next() ? new ApprovalRecord(
                    rs.getString("approval_id"), rs.getString("execution_id"), rs.getString("tool_name"),
                    rs.getString("tool_params"), rs.getString("status"), rs.getString("approved_by"),
                    rs.getTimestamp("expires_at").toInstant(), rs.getString("requester_user_id"),
                    rs.getObject("dataset_id", Long.class), rs.getObject("conversation_id", Long.class)) : null, id);
            if (loaded != null) records.put(id, loaded);
            return Optional.ofNullable(loaded);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> list() {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            return db.query("""
                    SELECT approval_id,execution_id,tool_name,tool_params::text,status,approved_by,
                           requester_user_id,dataset_id,conversation_id,create_time,
                           create_time + ttl_seconds * INTERVAL '1 second' AS expires_at
                    FROM kb_approval_record ORDER BY id DESC LIMIT 200
                    """, (rs, n) -> approvalMap(
                    rs.getString("approval_id"), rs.getString("execution_id"), rs.getString("tool_name"),
                    rs.getString("tool_params"), rs.getString("status"), rs.getString("approved_by"),
                    rs.getString("requester_user_id"), rs.getObject("dataset_id", Long.class),
                    rs.getObject("conversation_id", Long.class), rs.getTimestamp("create_time").toInstant().toString(),
                    rs.getTimestamp("expires_at").toInstant().toString()));
        } catch (Exception ignored) {
        }
        return records.values().stream().map(record -> approvalMap(record.id(), record.executionId(), record.toolName(),
                record.params(), record.status(), record.approvedBy(), record.requesterUserId(), record.datasetId(),
                record.conversationId(), record.expiresAt().minusSeconds(TTL_SECONDS).toString(), record.expiresAt().toString())).toList();
    }

    public List<Map<String, Object>> pendingFor(RequestUser user) {
        if (user == null || !user.hasRole("APPROVER")) return List.of();
        return list().stream()
                .filter(row -> "PENDING".equals(row.get("status")))
                .filter(row -> visibleToApprover(row, user))
                .toList();
    }

    @Scheduled(fixedDelay = 30000)
    public void expirePending() {
        records.replaceAll((id, record) -> {
            ApprovalRecord next = "PENDING".equals(record.status()) && Instant.now().isAfter(record.expiresAt())
                    ? record.status("TIMEOUT") : record;
            if (next != record) persist(next);
            return next;
        });
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("UPDATE kb_approval_record SET status='TIMEOUT',approved_at=NOW() WHERE status='PENDING' AND create_time + ttl_seconds * INTERVAL '1 second' < NOW()");
        } catch (Exception ignored) {
        }
    }

    private void persist(ApprovalRecord record) {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("""
                    INSERT INTO kb_approval_record(approval_id,execution_id,tool_name,tool_params,status,approved_by,requester_user_id,dataset_id,conversation_id,ttl_seconds)
                    VALUES (?,?,?,CAST(? AS jsonb),?,?,?,?,?,?)
                    ON CONFLICT(approval_id) DO UPDATE SET status=EXCLUDED.status,approved_by=EXCLUDED.approved_by,
                        requester_user_id=EXCLUDED.requester_user_id,dataset_id=EXCLUDED.dataset_id,conversation_id=EXCLUDED.conversation_id,
                        approved_at=CASE WHEN EXCLUDED.status='PENDING' THEN NULL ELSE NOW() END
                    """, record.id(), record.executionId(), record.toolName(), record.params(), record.status(),
                    record.approvedBy(), record.requesterUserId(), record.datasetId(), record.conversationId(), TTL_SECONDS);
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> approvalMap(String id, String executionId, String toolName, String params, String status,
            String approvedBy, String requesterUserId, Long datasetId, Long conversationId, String createdAt, String expiresAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("executionId", executionId);
        result.put("toolName", toolName);
        result.put("params", params);
        result.put("status", status);
        result.put("approvedBy", approvedBy);
        result.put("requesterUserId", requesterUserId);
        result.put("datasetId", datasetId);
        result.put("conversationId", conversationId);
        result.put("createdAt", createdAt);
        result.put("expiresAt", expiresAt);
        return result;
    }

    private boolean visibleToApprover(Map<String, Object> row, RequestUser user) {
        if (user.hasRole("ADMIN")) return true;
        Object requester = row.get("requesterUserId");
        if (requester != null && requester.equals(user.id())) return false;
        Object dataset = row.get("datasetId");
        return dataset instanceof Number number && user.canAccess(number.longValue());
    }
}
