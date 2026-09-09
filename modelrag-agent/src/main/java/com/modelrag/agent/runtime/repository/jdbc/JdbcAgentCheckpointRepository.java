package com.modelrag.agent.runtime.repository.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.runtime.AgentState;
import com.modelrag.agent.runtime.repository.AgentCheckpointRecord;
import com.modelrag.agent.runtime.repository.AgentCheckpointRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

/** Atomic PostgreSQL checkpoint writer using execution-row sequence CAS. */
@Repository
@Profile("!test")
public class JdbcAgentCheckpointRepository implements AgentCheckpointRepository {
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final ObjectMapper json;

    public JdbcAgentCheckpointRepository(JdbcTemplate jdbc, TransactionOperations transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public boolean save(AgentState state, String stateJson, String leaseOwner) {
        if (state == null || stateJson == null || leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("checkpoint state and lease owner are required");
        }
        long expected = state.checkpointSeq();
        long next = expected + 1;
        try {
            String budget = json.writeValueAsString(state.budgets());
            String result = json.writeValueAsString(state.result() == null ? java.util.Map.of() : state.result());
            Boolean committed = transactions.execute(transaction -> {
                int updated = jdbc.update("""
                        UPDATE kb_agent_execution
                        SET status=?, mode=?, goal=?, current_step=?, max_steps=?, deadline_at=?, state_version=?,
                            budget_json=CAST(? AS jsonb), result_json=CAST(? AS jsonb), last_checkpoint_seq=?,
                            error_code=CASE ?
                                WHEN 'RECONCILIATION_REQUIRED' THEN 'NON_IDEMPOTENT_ACTION_UNCERTAIN'
                                WHEN 'TIMEOUT' THEN 'DEADLINE_EXCEEDED'
                                WHEN 'CANCELLED' THEN 'CANCELLED'
                                WHEN 'ERROR' THEN 'AGENT_RUNTIME_ERROR'
                                ELSE NULL END,
                            error_msg=CASE WHEN ? IN ('RECONCILIATION_REQUIRED','TIMEOUT','CANCELLED','ERROR')
                                THEN LEFT(?,1000) ELSE NULL END,
                            lease_owner=CASE WHEN ? IN ('DONE','ERROR','TIMEOUT','CANCELLED','RECONCILIATION_REQUIRED',
                                                       'WAITING_APPROVAL') THEN NULL ELSE lease_owner END,
                            lease_until=CASE WHEN ? IN ('DONE','ERROR','TIMEOUT','CANCELLED','RECONCILIATION_REQUIRED',
                                                        'WAITING_APPROVAL') THEN NULL ELSE lease_until END,
                            finished_at=CASE WHEN ? IN ('DONE','ERROR','TIMEOUT','CANCELLED','RECONCILIATION_REQUIRED')
                                             THEN COALESCE(finished_at,NOW()) ELSE finished_at END,
                            update_time=NOW()
                        WHERE execution_id=? AND last_checkpoint_seq=? AND lease_owner=?
                          AND (status NOT IN ('CANCEL_REQUESTED','CANCELLED') OR ?='CANCELLED')
                        """, state.status().name(), state.mode(), state.goal(), state.currentStep(), state.maxSteps(),
                        Timestamp.from(state.deadlineAt()), state.stateVersion(), budget, result, next,
                        state.status().name(), state.status().name(),
                        state.result() == null ? "" : state.result().answer(),
                        state.status().name(), state.status().name(), state.status().name(), state.executionId(),
                        expected, leaseOwner, state.status().name());
                if (updated != 1) {
                    transaction.setRollbackOnly();
                    return false;
                }
                jdbc.update("""
                        INSERT INTO kb_agent_checkpoint(execution_id,checkpoint_seq,state_version,status,state_json)
                        VALUES (?,?,?, ?,CAST(? AS jsonb))
                        """, state.executionId(), next, state.stateVersion(), state.status().name(), stateJson);
                return true;
            });
            return Boolean.TRUE.equals(committed);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("agent checkpoint cannot be written", error);
        }
    }

    @Override
    public Optional<AgentCheckpointRecord> latest(String executionId) {
        return jdbc.query("""
                SELECT execution_id,checkpoint_seq,state_version,status,state_json::text,create_time
                FROM kb_agent_checkpoint WHERE execution_id=?
                ORDER BY checkpoint_seq DESC LIMIT 1
                """, (rs, row) -> new AgentCheckpointRecord(rs.getString("execution_id"),
                rs.getLong("checkpoint_seq"), rs.getInt("state_version"), rs.getString("status"),
                rs.getString("state_json"), instant(rs.getTimestamp("create_time"))), executionId)
                .stream().findFirst();
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
