package com.modelrag.agent.approval;

import com.modelrag.common.security.RequestUser;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * PostgreSQL is the source of truth for approval state; no process-local approval cache exists.
 */
@Service
@Profile("!test")
public class ApprovalGate {
    private static final int TTL_SECONDS = 300;
    private final JdbcTemplate jdbc;

    public ApprovalGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApprovalRecord request(String executionId, String tool, String params) {
        return request(executionId, tool, params, null, null, null);
    }

    public ApprovalRecord request(String executionId, String tool, String params, String requesterUserId,
                                  Long datasetId, Long conversationId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_approval_record(approval_id,execution_id,tool_name,tool_params,status,approved_by,
                requester_user_id,dataset_id,conversation_id,ttl_seconds)
                VALUES (?,?,?,CAST(? AS jsonb),'PENDING',NULL,?,?,?,?)
                """, id, executionId, tool, params, requesterUserId, datasetId, conversationId, TTL_SECONDS);
        return get(id).orElseThrow(() -> new IllegalStateException("审批记录写入后无法读取"));
    }

    public ApprovalRecord decide(String id, boolean approved) {
        return decide(id, approved, null);
    }

    public ApprovalRecord decide(String id, boolean approved, String userId) {
        int updated = jdbc.update("""
                UPDATE kb_approval_record SET status=?,approved_by=?,approved_at=NOW()
                WHERE approval_id=? AND status='PENDING'
                  AND create_time + ttl_seconds * INTERVAL '1 second' > NOW()
                """, approved ? "APPROVED" : "REJECTED", userId, id);
        if (updated == 0) {
            jdbc.update("""
                    UPDATE kb_approval_record SET status='TIMEOUT',approved_at=NOW()
                    WHERE approval_id=? AND status='PENDING'
                      AND create_time + ttl_seconds * INTERVAL '1 second' <= NOW()
                    """, id);
        }
        return get(id).orElseThrow(() -> new IllegalArgumentException("审批记录不存在"));
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
        return jdbc.query("""
                SELECT approval_id,execution_id,tool_name,tool_params::text,status,approved_by,
                       create_time + ttl_seconds * INTERVAL '1 second' AS expires_at,
                       requester_user_id,dataset_id,conversation_id
                FROM kb_approval_record WHERE approval_id=?
                """, (rs, n) -> new ApprovalRecord(rs.getString("approval_id"), rs.getString("execution_id"),
                rs.getString("tool_name"), rs.getString("tool_params"), rs.getString("status"),
                rs.getString("approved_by"), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("requester_user_id"), rs.getObject("dataset_id", Long.class),
                rs.getObject("conversation_id", Long.class)), id).stream().findFirst();
    }

    public List<ApprovalSnapshot> list() {
        return jdbc.query("""
                SELECT approval_id,execution_id,tool_name,tool_params::text,status,approved_by,
                       requester_user_id,dataset_id,conversation_id,create_time,
                       create_time + ttl_seconds * INTERVAL '1 second' AS expires_at
                FROM kb_approval_record ORDER BY id DESC LIMIT 200
                """, (rs, n) -> new ApprovalSnapshot(rs.getString("approval_id"), rs.getString("execution_id"),
                rs.getString("tool_name"), rs.getString("tool_params"), rs.getString("status"),
                rs.getString("approved_by"), rs.getString("requester_user_id"),
                rs.getObject("dataset_id", Long.class), rs.getObject("conversation_id", Long.class),
                rs.getTimestamp("create_time").toInstant().toString(),
                rs.getTimestamp("expires_at").toInstant().toString()));
    }

    public List<ApprovalSnapshot> pendingFor(RequestUser user) {
        if (user == null || !user.hasRole("APPROVER")) return List.of();
        return list().stream().filter(row -> "PENDING".equals(row.status()))
                .filter(row -> visibleToApprover(row, user)).toList();
    }

    @Scheduled(fixedDelay = 30_000)
    public void expirePending() {
        jdbc.update("""
                UPDATE kb_approval_record SET status='TIMEOUT',approved_at=NOW()
                WHERE status='PENDING' AND create_time + ttl_seconds * INTERVAL '1 second' < NOW()
                """);
    }

    private boolean visibleToApprover(ApprovalSnapshot row, RequestUser user) {
        if (user.hasRole("ADMIN")) return true;
        if (Objects.equals(row.requesterUserId(), user.id())) return false;
        return row.datasetId() != null && user.canAccess(row.datasetId());
    }
}
