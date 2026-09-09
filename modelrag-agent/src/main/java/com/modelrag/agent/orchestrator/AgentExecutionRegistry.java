package com.modelrag.agent.orchestrator;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.RequestUser;
import com.modelrag.agent.runtime.repository.AgentExecutionRepository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.modelrag.api.ModelInvocationCanceller;
import com.modelrag.toolgateway.execution.ToolExecutionCanceller;

/**
 * Authorization for an Agent execution is persisted with its trace rows.
 */
@Service
@Profile("!test")
public class AgentExecutionRegistry {
    private static final int MAX_ACTIVE_EXECUTIONS = 256;

    public record Scope(String requesterUserId, long datasetId, Long conversationId) {
    }

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, Thread> activeExecutions = new ConcurrentHashMap<>();
    private final Semaphore activeSlots = new Semaphore(MAX_ACTIVE_EXECUTIONS);
    private final ToolExecutionCanceller tools;
    private final ModelInvocationCanceller modelCalls;
    private final AgentExecutionRepository durableExecutions;

    public AgentExecutionRegistry(JdbcTemplate jdbc, ToolExecutionCanceller tools,
                                  ModelInvocationCanceller modelCalls) {
        this(jdbc, tools, modelCalls, (AgentExecutionRepository) null);
    }

    public AgentExecutionRegistry(JdbcTemplate jdbc, ToolExecutionCanceller tools,
                                  ModelInvocationCanceller modelCalls, AgentExecutionRepository durableExecutions) {
        this.jdbc = jdbc;
        this.tools = tools;
        this.modelCalls = modelCalls;
        this.durableExecutions = durableExecutions;
    }

    @Autowired
    public AgentExecutionRegistry(JdbcTemplate jdbc, ToolExecutionCanceller tools,
                                  ModelInvocationCanceller modelCalls,
                                  ObjectProvider<AgentExecutionRepository> durableExecutions) {
        this(jdbc, tools, modelCalls,
                durableExecutions == null ? null : durableExecutions.getIfAvailable());
    }

    public void register(String executionId, String requesterUserId, long datasetId, Long conversationId) {
        jdbc.update("""
                INSERT INTO kb_agent_execution(execution_id,user_id,dataset_id,conversation_id,status)
                VALUES (?,?,?,?,'RUNNING')
                ON CONFLICT(execution_id) DO NOTHING
                """, executionId, owner(requesterUserId), datasetId, conversationId);
        Scope existing = jdbc.query("""
                SELECT user_id,dataset_id,conversation_id FROM kb_agent_execution WHERE execution_id=?
                """, (rs, n) -> new Scope(rs.getString("user_id"), rs.getLong("dataset_id"),
                rs.getObject("conversation_id", Long.class)), executionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 执行不存在: " + executionId));
        if (!existing.requesterUserId().equals(owner(requesterUserId)) || existing.datasetId() != datasetId
                || !java.util.Objects.equals(existing.conversationId(), conversationId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Agent 执行标识已属于其他请求: " + executionId);
        }
    }

    public void complete(String executionId, String status) {
        if ("CANCELLED".equals(status)) {
            jdbc.update("UPDATE kb_agent_execution SET status=?,update_time=NOW() WHERE execution_id=?",
                    status, executionId);
            return;
        }
        jdbc.update("""
                UPDATE kb_agent_execution SET status=?,update_time=NOW()
                WHERE execution_id=? AND status NOT IN ('CANCEL_REQUESTED','CANCELLED')
                """, status, executionId);
    }

    public void requestCancel(RequestUser user, String executionId) {
        Scope scope = find(executionId);
        if (!user.hasRole("ADMIN") && !owner(user.id()).equals(scope.requesterUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权取消该 Agent 执行: " + executionId);
        }
        boolean updated = durableExecutions == null
                ? jdbc.update("""
                        UPDATE kb_agent_execution SET status='CANCEL_REQUESTED',update_time=NOW()
                        WHERE execution_id=? AND status IN ('RUNNING','WAITING_APPROVAL','CANCEL_REQUESTED')
                        """, executionId) > 0
                : durableExecutions.requestCancellation(executionId);
        if (!updated) return;

        // Local interruption is only an optimization. A missing local handle says nothing
        // about another replica that may own the PostgreSQL lease.
        interruptActive(executionId);
        if (durableExecutions != null) {
            durableExecutions.finalizeCancellationIfUnowned(executionId);
        } else {
            jdbc.update("""
                    UPDATE kb_agent_execution
                    SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,error_code='CANCELLED',
                        error_msg='Agent execution cancelled',
                        finished_at=COALESCE(finished_at,NOW()),update_time=NOW()
                    WHERE execution_id=? AND status='CANCEL_REQUESTED'
                      AND (lease_owner IS NULL OR lease_until < NOW())
                    """, executionId);
        }
    }

    /**
     * Ephemeral handles only; the persisted execution row remains the source of truth.
     */
    public void bind(String executionId, Thread thread) {
        if (executionId == null || thread == null) return;
        Thread current = activeExecutions.get(executionId);
        if (current == thread) return;
        if (current != null) throw new IllegalStateException("Agent 执行已绑定到其他工作线程");
        if (!activeSlots.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "并发 Agent 执行数已达上限");
        }
        if (activeExecutions.putIfAbsent(executionId, thread) != null) {
            activeSlots.release();
            throw new IllegalStateException("Agent 执行已绑定到其他工作线程");
        }
    }

    public void unbind(String executionId, Thread thread) {
        if (executionId != null && thread != null && activeExecutions.remove(executionId, thread)) {
            activeSlots.release();
        }
    }

    public boolean interruptActive(String executionId) {
        Thread thread = activeExecutions.get(executionId);
        if (thread == null) return false;
        modelCalls.cancel(thread);
        tools.cancel(thread);
        thread.interrupt();
        return true;
    }

    public boolean cancelRequested(String executionId) {
        return jdbc.query("SELECT status FROM kb_agent_execution WHERE execution_id=?", (rs, n) -> rs.getString(1),
                executionId).stream().anyMatch(status -> "CANCEL_REQUESTED".equals(status) || "CANCELLED".equals(status));
    }

    public void requireSubscribe(RequestUser user, String executionId) {
        Scope scope = find(executionId);
        if (user.hasRole("ADMIN") || owner(user.id()).equals(scope.requesterUserId())) return;
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权订阅该 Agent 执行流: " + executionId);
    }

    private Scope find(String executionId) {
        return jdbc.query("""
                        SELECT user_id,dataset_id,conversation_id FROM kb_agent_execution WHERE execution_id=?
                        """, (rs, n) -> new Scope(rs.getString("user_id"), rs.getLong("dataset_id"),
                        rs.getObject("conversation_id", Long.class)), executionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 执行不存在: " + executionId));
    }

    private String owner(String value) {
        return value == null || value.isBlank() ? "global" : value;
    }
}
