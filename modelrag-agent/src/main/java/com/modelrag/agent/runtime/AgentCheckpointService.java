package com.modelrag.agent.runtime;

import com.modelrag.agent.runtime.repository.AgentCheckpointRecord;
import com.modelrag.agent.runtime.repository.AgentCheckpointRepository;
import com.modelrag.agent.runtime.repository.AgentExecutionRepository;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Coordinates bounded state encoding, optimistic checkpoints and PostgreSQL leases. */
@Service
@Profile("!test")
public class AgentCheckpointService {
    private final AgentExecutionRepository executions;
    private final AgentCheckpointRepository checkpoints;
    private final AgentStateCodec codec;
    private final Duration lease;

    public AgentCheckpointService(AgentExecutionRepository executions, AgentCheckpointRepository checkpoints,
            AgentStateCodec codec) {
        this(executions, checkpoints, codec, 30);
    }

    public AgentCheckpointService(AgentExecutionRepository executions, AgentCheckpointRepository checkpoints,
            AgentStateCodec codec, @Value("${modelrag.agent.runtime.lease-seconds:30}") long leaseSeconds) {
        this.executions = executions;
        this.checkpoints = checkpoints;
        this.codec = codec;
        this.lease = Duration.ofSeconds(Math.max(1, Math.min(86_400, leaseSeconds)));
    }

    /** Inserts a new execution, claims its lease, and writes the initial seq=1 checkpoint. */
    public AgentState createAndCheckpoint(AgentState state, String owner) {
        executions.create(state);
        if (!executions.tryClaim(state.executionId(), owner, lease)) {
            throw new AgentLeaseUnavailableException("agent execution lease is not available");
        }
        return checkpoint(state, owner);
    }

    /** Writes nextSeq only if the caller still owns the expected checkpoint sequence. */
    public AgentState checkpoint(AgentState state, String owner) {
        if (state == null) throw new IllegalArgumentException("agent state is required");
        if (!executions.renew(state.executionId(), owner, lease)) {
            throw new AgentLeaseUnavailableException("agent execution lease is no longer owned");
        }
        AgentState next = state.withCheckpointSeq(state.checkpointSeq() + 1);
        String encoded = codec.encode(next);
        if (!checkpoints.save(state, encoded, owner)) {
            throw new AgentCheckpointConflictException("stale agent checkpoint sequence");
        }
        return next;
    }

    public Optional<AgentState> loadLatest(String executionId) {
        Optional<AgentCheckpointRecord> latest = checkpoints.latest(executionId);
        return latest.map(row -> {
            AgentState state = codec.decode(row.stateJson());
            if (state.checkpointSeq() != row.checkpointSeq()) {
                throw new IllegalStateException("checkpoint sequence does not match state JSON");
            }
            return state;
        });
    }

    public boolean tryClaim(String executionId, String owner) {
        return executions.tryClaim(executionId, owner, lease);
    }

    public boolean tryClaimWaiting(String executionId, String owner) {
        return executions.tryClaimWaiting(executionId, owner, lease);
    }

    public boolean renew(String executionId, String owner) {
        return executions.renew(executionId, owner, lease);
    }

    public boolean release(String executionId, String owner) {
        return executions.release(executionId, owner);
    }

    public Duration lease() { return lease; }
}
