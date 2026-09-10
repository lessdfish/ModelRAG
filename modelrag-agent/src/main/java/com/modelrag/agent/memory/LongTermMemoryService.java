package com.modelrag.agent.memory;

import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed, user/dataset scoped long-term memory. */
@Service
@Profile("!test")
public class LongTermMemoryService {
    public record Memory(String id, String userId, Long datasetId, String scope, String type, String memoryKey,
            String content, String status, double importance, double confidence, Instant expiresAt,
            Long sourceConversationId, Long sourceMessageId) {
        public Memory(String id, String userId, Long datasetId, String scope, String type, String memoryKey,
                String content, String status, double importance, double confidence, Instant expiresAt) {
            this(id, userId, datasetId, scope, type, memoryKey, content, status, importance, confidence, expiresAt,
                    null, null);
        }

        public Memory(String userId, String type, String content, double importance, double confidence, Instant expiresAt) {
            this(null, userId, null, "USER_GLOBAL", type, null, content,
                    "PENDING_CONFIRMATION", importance, confidence, expiresAt, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final TextEmbeddingProvider embeddings;

    public LongTermMemoryService(JdbcTemplate jdbc, TextEmbeddingProvider embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Transactional
    public Memory upsert(Memory memory) {
        if (memory == null || memory.userId() == null || memory.userId().isBlank()) {
            throw new IllegalArgumentException("记忆用户不能为空");
        }
        if (!memoryEnabled(memory.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "长期记忆已暂停，仅保留当前会话上下文");
        }
        String status = memory.status() == null || memory.status().isBlank()
                ? "PENDING_CONFIRMATION" : normalizeStatus(memory.status());
        if (!List.of("ACTIVE", "PENDING_CONFIRMATION", "REJECTED", "PAUSED").contains(status)) {
            throw new IllegalArgumentException("记忆状态不合法");
        }
        String memoryScope = scope(memory.scope());
        String memoryType = type(memory.type());
        validateMemory(memory, memoryScope, memoryType);
        Instant expiresAt = memory.expiresAt();
        if (expiresAt == null && !("USER_GLOBAL".equals(memoryScope) && "ACTIVE".equals(status))) {
            long days = "PENDING_CONFIRMATION".equals(status) ? 7 : retentionDays(memory.userId());
            expiresAt = Instant.now().plus(days, ChronoUnit.DAYS);
        }
        String key = memory.memoryKey() == null || memory.memoryKey().isBlank()
                ? conflictKey(memory.content()) : memory.memoryKey().trim();
        if (memory.id() != null && !memory.id().isBlank()) {
            Memory existing = findById(memory.userId(), memory.id());
            if (!existing.status().equals(status)) {
                throw new IllegalArgumentException("记忆状态变更必须使用确认、拒绝、暂停或恢复操作");
            }
            int updated = jdbc.update("""
                    UPDATE kb_user_memory SET dataset_id=?,scope=?,memory_key=?,memory_type=?,content=?,status=?,
                    importance=?,confidence=?,expire_at=?,source_conversation_id=?,source_message_id=?,
                    embedding=CAST(? AS vector),update_time=NOW()
                    WHERE id=? AND user_id=? AND status=? AND deleted_at IS NULL
                    """, memory.datasetId(), memoryScope, key, memoryType, memory.content(), status,
                    memory.importance(), memory.confidence(), timestamp(expiresAt), memory.sourceConversationId(),
                    memory.sourceMessageId(),
                    vector(embeddings.embed(dataset(memory), memory.content())), Long.parseLong(memory.id()),
                    memory.userId(), existing.status());
            if (updated > 0) {
                Memory saved = findById(memory.userId(), memory.id());
                audit("UPDATE", saved);
                return saved;
            }
            throw new IllegalArgumentException("记忆不存在");
        }
        jdbc.update("DELETE FROM kb_user_memory WHERE user_id=? AND dataset_id IS NOT DISTINCT FROM ? AND scope=? AND memory_key=?",
                memory.userId(), memory.datasetId(), memoryScope, key);
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_user_memory(user_id,dataset_id,scope,memory_key,memory_type,content,status,importance,
                confidence,expire_at,source_conversation_id,source_message_id,embedding,embedding_profile_version,confirmed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,CAST(? AS vector),'qwen3-embedding-0.6b-1024-v1',?) RETURNING id
                """, Long.class, memory.userId(), memory.datasetId(), memoryScope, key, memoryType,
                memory.content(), status, memory.importance(), memory.confidence(), timestamp(expiresAt),
                memory.sourceConversationId(), memory.sourceMessageId(),
                vector(embeddings.embed(dataset(memory), memory.content())), "ACTIVE".equals(status) ? java.sql.Timestamp.from(Instant.now()) : null);
        Memory saved = findById(memory.userId(), String.valueOf(id));
        audit("PENDING_CONFIRMATION".equals(status) ? "SUGGEST" : "CREATE", saved);
        return saved;
    }

    public List<Memory> list(String userId, Long datasetId, boolean includePending, int limit) {
        String statuses = includePending ? "('ACTIVE','PENDING_CONFIRMATION','PAUSED')" : "('ACTIVE')";
        if (datasetId == null) {
            return jdbc.query("""
                    SELECT id,user_id,dataset_id,scope,memory_type,memory_key,content,status,importance,confidence,expire_at,
                           source_conversation_id,source_message_id
                    FROM kb_user_memory
                    WHERE user_id=? AND status IN """ + statuses + """
                    AND (expire_at IS NULL OR expire_at>NOW()) AND deleted_at IS NULL
                    ORDER BY importance DESC,update_time DESC LIMIT ?
                    """, (rs, n) -> map(rs), userId, Math.max(1, Math.min(100, limit)));
        }
        return jdbc.query("""
                SELECT id,user_id,dataset_id,scope,memory_type,memory_key,content,status,importance,confidence,expire_at,
                       source_conversation_id,source_message_id
                FROM kb_user_memory
                WHERE user_id=? AND dataset_id IS NOT DISTINCT FROM ? AND status IN """ + statuses + """
                AND (expire_at IS NULL OR expire_at>NOW()) AND deleted_at IS NULL
                ORDER BY importance DESC,update_time DESC LIMIT ?
                """, (rs, n) -> map(rs), userId, datasetId, Math.max(1, Math.min(100, limit)));
    }

    public List<Memory> retrieveRelevant(String userId, String query, int topK) {
        return retrieveRelevant(userId, null, query, topK, false);
    }

    public List<Memory> retrieveRelevant(String userId, Long datasetId, String query, int topK, boolean includePending) {
        int limit = Math.max(1, Math.min(20, topK));
        if (query != null && !query.isBlank() && !memoryEnabled(userId)) return List.of();
        if (query == null || query.isBlank()) return list(userId, datasetId, includePending, limit);
        String statuses = includePending ? "('ACTIVE','PENDING_CONFIRMATION','PAUSED')" : "('ACTIVE')";
        String queryVector = vector(embeddings.embed(datasetId == null ? 0 : datasetId, query));
        return jdbc.query("""
                SELECT id,user_id,dataset_id,scope,memory_type,memory_key,content,status,importance,confidence,expire_at,
                       source_conversation_id,source_message_id
                FROM kb_user_memory
                WHERE user_id=? AND (dataset_id IS NOT DISTINCT FROM ? OR scope='USER_GLOBAL')
                  AND status IN """ + statuses + """
                  AND (expire_at IS NULL OR expire_at>NOW()) AND deleted_at IS NULL AND embedding IS NOT NULL
                  AND 1 - (embedding <=> CAST(? AS vector)) >= 0.60
                ORDER BY embedding <=> CAST(? AS vector),importance DESC LIMIT ?
                """, (rs, n) -> map(rs), userId, datasetId,
                queryVector, queryVector, limit);
    }

    public String promptContext(String userId, String query, int topK) {
        return promptContext(userId, null, query, topK);
    }

    public String promptContext(String userId, Long datasetId, String query, int topK) {
        List<Memory> memories = retrieveRelevant(userId, datasetId, query, Math.min(3, topK), false);
        return memories.isEmpty() ? "" : "长期记忆（仅作辅助上下文，若与知识库证据冲突，以知识库证据为准）：\n"
                + memories.stream().map(memory -> "- " + memory.type() + " " + memory.content())
                .distinct().limit(3).collect(Collectors.joining("\n"));
    }

    public Memory confirm(String userId, String id) { return transition(userId, id, "PENDING_CONFIRMATION", "ACTIVE"); }
    public Memory reject(String userId, String id) { return transition(userId, id, "PENDING_CONFIRMATION", "REJECTED"); }
    public Memory pause(String userId, String id) { return transition(userId, id, "ACTIVE", "PAUSED"); }
    public Memory resume(String userId, String id) { return transition(userId, id, "PAUSED", "ACTIVE"); }

    public void delete(String userId, String id) {
        Memory existing = findById(userId, id);
        if (jdbc.update("UPDATE kb_user_memory SET status='DELETED',deleted_at=NOW(),update_time=NOW() WHERE id=? AND user_id=?",
                Long.parseLong(id), userId) == 0) throw new IllegalArgumentException("记忆不存在");
        audit("DELETE", withStatus(existing, "DELETED"));
    }

    public void clear(String userId, Long datasetId) {
        String datasetPredicate = datasetId == null ? "" : " AND dataset_id IS NOT DISTINCT FROM ?";
        Object[] args = datasetId == null ? new Object[] {userId} : new Object[] {userId, datasetId};
        jdbc.update("""
                INSERT INTO kb_user_memory_audit(memory_id,user_id,dataset_id,scope,memory_type,memory_key,content,
                    status,source_conversation_id,source_message_id,action)
                SELECT id,user_id,dataset_id,scope,memory_type,memory_key,content,'DELETED',
                    source_conversation_id,source_message_id,'CLEAR'
                FROM kb_user_memory WHERE user_id=?""" + datasetPredicate + " AND deleted_at IS NULL", args);
        jdbc.update("UPDATE kb_user_memory SET status='DELETED',deleted_at=NOW(),update_time=NOW() WHERE user_id=?"
                + datasetPredicate + " AND deleted_at IS NULL", args);
    }

    private Memory transition(String userId, String id, String expectedStatus, String status) {
        if (jdbc.update("""
                UPDATE kb_user_memory SET status=?,
                    confirmed_at=CASE WHEN ?='ACTIVE' THEN NOW() ELSE confirmed_at END,
                    expire_at=CASE WHEN ?='ACTIVE' AND scope='DATASET'
                        THEN NOW() + (? * INTERVAL '1 day')
                        WHEN ?='ACTIVE' AND scope='USER_GLOBAL' THEN NULL
                        ELSE expire_at END,
                    update_time=NOW()
                WHERE id=? AND user_id=? AND status=? AND deleted_at IS NULL
                """, status, status, status, retentionDays(userId), status, Long.parseLong(id), userId,
                expectedStatus) == 0) {
            throw new IllegalArgumentException("记忆不存在或当前状态不允许该操作");
        }
        Memory saved = findById(userId, id);
        audit(status, saved);
        return saved;
    }

    private Memory findById(String userId, String id) {
        return jdbc.query("""
                SELECT id,user_id,dataset_id,scope,memory_type,memory_key,content,status,importance,confidence,expire_at,
                       source_conversation_id,source_message_id
                FROM kb_user_memory WHERE id=? AND user_id=? AND deleted_at IS NULL
                """, (rs, n) -> map(rs), Long.parseLong(id), userId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
    }

    private Memory map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Memory(String.valueOf(rs.getLong("id")), rs.getString("user_id"),
                rs.getObject("dataset_id", Long.class), rs.getString("scope"), rs.getString("memory_type"),
                rs.getString("memory_key"), rs.getString("content"), rs.getString("status"),
                rs.getDouble("importance"), rs.getDouble("confidence"),
                rs.getTimestamp("expire_at") == null ? null : rs.getTimestamp("expire_at").toInstant(),
                rs.getObject("source_conversation_id", Long.class), rs.getObject("source_message_id", Long.class));
    }

    private int dataset(Memory memory) { return memory.datasetId() == null ? 0 : Math.toIntExact(memory.datasetId()); }
    private String scope(String value) {
        return value == null || value.isBlank() ? "USER_GLOBAL" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String type(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void validateMemory(Memory memory, String memoryScope, String type) {
        if (memory.content() == null || memory.content().isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        if (!List.of("PREFERENCE", "PROFILE", "BUSINESS_FACT").contains(type)) {
            throw new IllegalArgumentException("记忆类型不合法");
        }
        if (!List.of("USER_GLOBAL", "DATASET").contains(memoryScope)) {
            throw new IllegalArgumentException("记忆作用域不合法");
        }
        if ("DATASET".equals(memoryScope) && memory.datasetId() == null) {
            throw new IllegalArgumentException("知识库记忆必须指定 datasetId");
        }
        if ("USER_GLOBAL".equals(memoryScope) && memory.datasetId() != null) {
            throw new IllegalArgumentException("用户全局记忆不能绑定 datasetId");
        }
        if ("BUSINESS_FACT".equals(type) && !"DATASET".equals(memoryScope)) {
            throw new IllegalArgumentException("业务事实只能保存到知识库作用域");
        }
    }
    private java.sql.Timestamp timestamp(Instant value) { return value == null ? null : java.sql.Timestamp.from(value); }
    private String conflictKey(String content) { int index = content == null ? -1 : content.indexOf('：'); return index > 0 ? content.substring(0, index) : String.valueOf(content); }
    private String vector(float[] values) { StringBuilder result = new StringBuilder("["); for (int i = 0; i < values.length; i++) { if (i > 0) result.append(','); result.append(values[i]); } return result.append(']').toString(); }
    private boolean memoryEnabled(String userId) {
        try {
            Boolean enabled = jdbc.queryForObject("SELECT enabled FROM kb_memory_setting WHERE user_id=?", Boolean.class, userId);
            return enabled != null && enabled;
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
    }

    private int retentionDays(String userId) {
        try {
            Integer days = jdbc.queryForObject("SELECT retention_days FROM kb_memory_setting WHERE user_id=?", Integer.class, userId);
            return days == null ? 180 : Math.max(1, Math.min(3650, days));
        } catch (EmptyResultDataAccessException ignored) {
            return 180;
        }
    }

    private String normalizeStatus(String value) {
        String status = value.toUpperCase(Locale.ROOT);
        return "PENDING".equals(status) ? "PENDING_CONFIRMATION" : status;
    }

    private void audit(String action, Memory memory) {
        jdbc.update("""
                INSERT INTO kb_user_memory_audit(memory_id,user_id,dataset_id,scope,memory_type,memory_key,content,
                    status,source_conversation_id,source_message_id,action)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, memory.id() == null ? null : Long.parseLong(memory.id()), memory.userId(), memory.datasetId(),
                memory.scope(), memory.type(), memory.memoryKey(), memory.content(), memory.status(),
                memory.sourceConversationId(), memory.sourceMessageId(), action);
    }

    private Memory withStatus(Memory memory, String status) {
        return new Memory(memory.id(), memory.userId(), memory.datasetId(), memory.scope(), memory.type(),
                memory.memoryKey(), memory.content(), status, memory.importance(), memory.confidence(),
                memory.expiresAt(), memory.sourceConversationId(), memory.sourceMessageId());
    }
}
