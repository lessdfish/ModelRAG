package com.modelrag.agent.runtime.repository.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.runtime.AgentState;
import com.modelrag.agent.runtime.repository.AgentExecutionRecord;
import com.modelrag.agent.runtime.repository.AgentExecutionRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL execution summary, lease and bounded recovery queries. */
@Repository
@Profile("!test")
public class JdbcAgentExecutionRepository implements AgentExecutionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcAgentExecutionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void create(AgentState state) {
        try {
            String budget = json.writeValueAsString(state.budgets());
            String result = json.writeValueAsString(state.result() == null ? java.util.Map.of() : state.result());
            int inserted = jdbc.update("""
                    INSERT INTO kb_agent_execution(execution_id,user_id,dataset_id,conversation_id,status,mode,goal,
                        current_step,max_steps,deadline_at,state_version,budget_json,result_json,last_checkpoint_seq)
                    VALUES (?,?,?,?,?,?,?, ?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),0)
                    ON CONFLICT(execution_id) DO NOTHING
                    """, state.executionId(), state.userId(), state.datasetId(), state.conversationId(),
                    state.status().name(), state.mode(), state.goal(), state.currentStep(), state.maxSteps(),
                    Timestamp.from(state.deadlineAt()), state.stateVersion(), budget, result);
            if (inserted == 0) {
                AgentExecutionRecord existing = findById(state.executionId())
                        .orElseThrow(() -> new IllegalStateException("execution disappeared after duplicate insert"));
                if (!existing.userId().equals(state.userId()) || existing.datasetId() != state.datasetId()
                        || !java.util.Objects.equals(existing.conversationId(), state.conversationId())) {
                    throw new IllegalArgumentException("execution scope does not match existing execution");
                }
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("agent execution cannot be created", error);
        }
    }

    @Override
    public Optional<AgentExecutionRecord> findById(String executionId) {
        return jdbc.query("""
                SELECT execution_id,user_id,dataset_id,conversation_id,mode,goal,status,current_step,max_steps,
                       deadline_at,state_version,last_checkpoint_seq,lease_owner,lease_until,error_code,error_msg,finished_at
                FROM kb_agent_execution WHERE execution_id=?
                """, (rs, row) -> new AgentExecutionRecord(rs.getString("execution_id"), rs.getString("user_id"),
                rs.getLong("dataset_id"), rs.getObject("conversation_id", Long.class), rs.getString("mode"),
                rs.getString("goal"), rs.getString("status"), rs.getInt("current_step"), rs.getInt("max_steps"),
                instant(rs.getTimestamp("deadline_at")), rs.getInt("state_version"), rs.getLong("last_checkpoint_seq"),
                rs.getString("lease_owner"), instant(rs.getTimestamp("lease_until")), rs.getString("error_code"),
                rs.getString("error_msg"), instant(rs.getTimestamp("finished_at"))), executionId)
                .stream().findFirst();
    }

    @Override
    public boolean tryClaim(String executionId, String owner, Duration lease) {
        requireOwner(owner);
        long seconds = boundedSeconds(lease);
        return jdbc.update("""
                UPDATE kb_agent_execution
                SET lease_owner=?, lease_until=NOW() + (? * INTERVAL '1 second'), update_time=NOW()
                WHERE execution_id=? AND status='RUNNING'
                  AND (lease_until IS NULL OR lease_until < NOW() OR lease_owner=?)
                """, owner, seconds, executionId, owner) == 1;
    }

    @Override
    public boolean tryClaimWaiting(String executionId, String owner, Duration lease) {
        requireOwner(owner);
        return jdbc.update("""
                UPDATE kb_agent_execution
                SET status='RUNNING', lease_owner=?, lease_until=NOW() + (? * INTERVAL '1 second'), update_time=NOW()
                WHERE execution_id=? AND status='WAITING_APPROVAL' AND lease_owner IS NULL
                """, owner, boundedSeconds(lease), executionId) == 1;
    }

    @Override
    public boolean renew(String executionId, String owner, Duration lease) {
        requireOwner(owner);
        return jdbc.update("""
                UPDATE kb_agent_execution
                SET lease_until=NOW() + (? * INTERVAL '1 second'), update_time=NOW()
                WHERE execution_id=? AND status IN ('RUNNING','CANCEL_REQUESTED','CANCELLED') AND lease_owner=?
                """, boundedSeconds(lease), executionId, owner) == 1;
    }

    @Override
    public boolean release(String executionId, String owner) {
        requireOwner(owner);
        return jdbc.update("""
                UPDATE kb_agent_execution SET lease_owner=NULL, lease_until=NULL, update_time=NOW()
                WHERE execution_id=? AND lease_owner=?
                """, executionId, owner) == 1;
    }

    @Override
    public boolean requestCancellation(String executionId) {
        return jdbc.update("""
                UPDATE kb_agent_execution SET status='CANCEL_REQUESTED',update_time=NOW()
                WHERE execution_id=? AND status IN ('RUNNING','WAITING_APPROVAL','CANCEL_REQUESTED')
                """, executionId) == 1;
    }

    @Override
    public boolean finalizeCancellationIfUnowned(String executionId) {
        return jdbc.update("""
                UPDATE kb_agent_execution
                SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,error_code='CANCELLED',
                    error_msg='Agent execution cancelled',
                    finished_at=COALESCE(finished_at,NOW()),update_time=NOW()
                WHERE execution_id=? AND status='CANCEL_REQUESTED'
                  AND (lease_owner IS NULL OR lease_until < NOW())
                """, executionId) == 1;
    }

    @Override
    public int finalizeExpiredCancellations(int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return jdbc.update("""
                WITH candidates AS (
                    SELECT execution_id
                    FROM kb_agent_execution
                    WHERE status='CANCEL_REQUESTED'
                      AND (lease_owner IS NULL OR lease_until < NOW())
                    ORDER BY update_time
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE kb_agent_execution execution
                SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,error_code='CANCELLED',
                    error_msg='Agent execution cancelled',
                    finished_at=COALESCE(execution.finished_at,NOW()),update_time=NOW()
                FROM candidates
                WHERE execution.execution_id=candidates.execution_id
                """, bounded);
    }

    @Override
    public List<String> findRecoverable(int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return jdbc.query("""
                SELECT execution_id FROM kb_agent_execution
                WHERE status='RUNNING'
                  AND mode IS NOT NULL
                  AND last_checkpoint_seq > 0
                  AND (lease_until IS NULL OR lease_until < NOW())
                ORDER BY update_time
                LIMIT ?
                """, (rs, row) -> rs.getString(1), bounded);
    }

    private long boundedSeconds(Duration lease) {
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return Math.max(1, Math.min(86_400, lease.toSeconds()));
    }

    private void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 160) {
            throw new IllegalArgumentException("runtime owner is invalid");
        }
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
