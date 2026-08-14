package com.modelrag.agent.orchestrator;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.RequestUser;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.modelrag.agent.tool.HttpToolInvoker;
import com.modelrag.agent.tool.ResilientToolExecutor;
import com.modelrag.api.ModelInvocationCanceller;

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
    private final ResilientToolExecutor tools;
    private final HttpToolInvoker httpTools;
    private final ModelInvocationCanceller modelCalls;

    public AgentExecutionRegistry(JdbcTemplate jdbc, ResilientToolExecutor tools, HttpToolInvoker httpTools,
                                  ModelInvocationCanceller modelCalls) {
        this.jdbc = jdbc;
        this.tools = tools;
        this.httpTools = httpTools;
        this.modelCalls = modelCalls;
    }

    public void register(String executionId, String requesterUserId, long datasetId, Long conversationId) {
        jdbc.update("""
                INSERT INTO kb_agent_execution(execution_id,user_id,dataset_id,conversation_id,status)
                VALUES (?,?,?,?,'RUNNING')
                ON CONFLICT(execution_id) DO UPDATE SET user_id=EXCLUDED.user_id,dataset_id=EXCLUDED.dataset_id,
                conversation_id=EXCLUDED.conversation_id,status='RUNNING',update_time=NOW()
                """, executionId, owner(requesterUserId), datasetId, conversationId);
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
        int updated = jdbc.update("""
                UPDATE kb_agent_execution SET status='CANCEL_REQUESTED',update_time=NOW()
                WHERE execution_id=? AND status IN ('RUNNING','WAITING_APPROVAL','CANCEL_REQUESTED')
                """, executionId);
        if (updated > 0 && !interruptActive(executionId)) complete(executionId, "CANCELLED");
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
        Thread toolWorker = tools.activeWorker(thread);
        if (toolWorker != null) httpTools.cancel(toolWorker);
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
