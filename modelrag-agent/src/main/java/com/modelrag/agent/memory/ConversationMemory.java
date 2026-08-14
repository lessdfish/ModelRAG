package com.modelrag.agent.memory;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.ConversationAccess;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed conversation and summary store. */
@Service
@Profile("!test")
public class ConversationMemory implements ConversationAccess {
    private static final int SUMMARY_INTERVAL = 12;
    private static final int RECENT_MESSAGES = 8;
    private static final Pattern LEGACY_CITATION = Pattern.compile("\\[(\\d+)]");
    private final JdbcTemplate jdbc;

    public record Entry(String role, String content, String citations, long createdAt, String traceId,
            String mode, String datasetName, long messageId) {
        public Entry(String role, String content, String citations, long createdAt, String traceId,
                String mode, String datasetName) {
            this(role, content, citations, createdAt, traceId, mode, datasetName, 0);
        }

        public Entry(String role, String content, String citations, long createdAt) {
            this(role, content, citations, createdAt, null, null, null, 0);
        }
    }

    public record Conversation(long id, Long datasetId, String title, int messageCount,
            Instant updateTime, String userId) {}

    public ConversationMemory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long create(Long datasetId, String title) { return create("global", datasetId, title); }

    public long create(String userId, Long datasetId, String title) {
        String owner = owner(userId);
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_conversation(user_id,dataset_id,title,model)
                VALUES (?,?,?,?) RETURNING id
                """, Long.class, owner, datasetId, title == null ? "新会话" : title.trim(), "spring-ai");
        return id;
    }

    public List<Conversation> conversations(boolean includeArchived) {
        return conversations("global", includeArchived);
    }

    public List<Conversation> conversations(String userId, boolean includeArchived) {
        return jdbc.query("""
                SELECT id,dataset_id,title,message_count,update_time,user_id
                FROM kb_conversation
                WHERE user_id=? AND (? OR archive_time IS NULL)
                ORDER BY update_time DESC,id DESC LIMIT 100
                """, (rs, n) -> new Conversation(rs.getLong("id"), rs.getObject("dataset_id", Long.class),
                rs.getString("title"), rs.getInt("message_count"), rs.getTimestamp("update_time").toInstant(),
                rs.getString("user_id")), owner(userId), includeArchived);
    }

    public Optional<Conversation> conversation(String userId, long conversationId, boolean includeArchived) {
        return jdbc.query("""
                SELECT id,dataset_id,title,message_count,update_time,user_id
                FROM kb_conversation
                WHERE id=? AND user_id=? AND (? OR archive_time IS NULL)
                """, (rs, n) -> new Conversation(rs.getLong("id"), rs.getObject("dataset_id", Long.class),
                rs.getString("title"), rs.getInt("message_count"), rs.getTimestamp("update_time").toInstant(),
                rs.getString("user_id")), conversationId, owner(userId), includeArchived).stream().findFirst();
    }

    public void archive(long conversationId) { archive("global", conversationId); }

    public void archive(String userId, long conversationId) {
        int updated = jdbc.update("UPDATE kb_conversation SET archive_time=NOW(),update_time=NOW() WHERE id=? AND user_id=?",
                conversationId, owner(userId));
        if (updated == 0) throw notFound(conversationId);
    }

    @Transactional
    public void delete(String userId, long conversationId) {
        String owner = owner(userId);
        Long locked = jdbc.query("SELECT id FROM kb_conversation WHERE id=? AND user_id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1), conversationId, owner).stream().findFirst().orElse(null);
        if (locked == null) throw notFound(conversationId);
        jdbc.update("DELETE FROM kb_context_summary_task WHERE conversation_id=?", conversationId);
        jdbc.update("DELETE FROM kb_context_summary WHERE conversation_id=?", conversationId);
        jdbc.update("DELETE FROM kb_message WHERE conversation_id=?", conversationId);
        jdbc.update("DELETE FROM kb_conversation WHERE id=? AND user_id=?", conversationId, owner);
    }

    public void append(long conversationId, String role, String content) {
        append("global", conversationId, role, content, "[]", null, null, null);
    }

    public void append(long conversationId, String role, String content, String citations) {
        append("global", conversationId, role, content, citations, null, null, null);
    }

    public void append(long conversationId, String role, String content, String citations,
            String traceId, String mode, String datasetName) {
        append("global", conversationId, role, content, citations, traceId, mode, datasetName);
    }

    @Transactional
    public void append(String userId, long conversationId, String role, String content, String citations,
            String traceId, String mode, String datasetName) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("消息内容不能为空");
        String saved = citations == null || citations.isBlank() ? "[]" : citations;
        int updated = jdbc.update("""
                INSERT INTO kb_message(conversation_id,role,content,citations,trace_id,mode,dataset_name)
                SELECT id,?,?,CAST(? AS jsonb),?,?,? FROM kb_conversation
                WHERE id=? AND user_id=? AND archive_time IS NULL
                """, role, content, saved, traceId, mode, datasetName, conversationId, owner(userId));
        if (updated == 0) throw notFound(conversationId);
        jdbc.update("UPDATE kb_conversation SET message_count=message_count+1,update_time=NOW() WHERE id=? AND user_id=?",
                conversationId, owner(userId));
        enqueueSummaryIfNeeded(owner(userId), conversationId);
    }

    public String contextualQuery(long conversationId, String query) {
        return contextualQuery("global", conversationId, query);
    }

    public String contextualQuery(String userId, long conversationId, String query) {
        String summary = summary(userId, conversationId).orElse("");
        List<Entry> recent = history(userId, conversationId, 1, RECENT_MESSAGES);
        String recentText = recent.stream().map(entry -> entry.role() + ":" + entry.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        String context = (summary.isBlank() ? "" : "会话摘要（仅作上下文）：" + summary + "\n")
                + (recentText.isBlank() ? "" : "最近消息（仅作上下文）：\n" + recentText);
        return context.isBlank() ? query : query + "\n\n" + context;
    }

    public Optional<String> summary(long conversationId) { return summary("global", conversationId); }

    public Optional<String> summary(String userId, long conversationId) {
        return jdbc.query("""
                SELECT s.summary FROM kb_context_summary s
                JOIN kb_conversation c ON c.id=s.conversation_id
                WHERE s.conversation_id=? AND c.user_id=? AND s.status='READY'
                ORDER BY s.create_time DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), conversationId, owner(userId)).stream().findFirst();
    }

    public List<Entry> history(long conversationId, int page, int size) {
        return history("global", conversationId, page, size);
    }

    public List<Entry> history(String userId, long conversationId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(10_000, size));
        return jdbc.query("""
                SELECT m.id,m.role,m.content,m.citations,m.create_time,m.trace_id,m.mode,m.dataset_name
                FROM kb_message m JOIN kb_conversation c ON c.id=m.conversation_id
                WHERE m.conversation_id=? AND c.user_id=?
                ORDER BY m.create_time,m.id OFFSET ? LIMIT ?
                """, (rs, n) -> {
                    String content = rs.getString("content");
                    return new Entry(rs.getString("role"), content,
                            citationsForHistory(rs.getString("citations"), content),
                            rs.getTimestamp("create_time").getTime(), rs.getString("trace_id"),
                            rs.getString("mode"), rs.getString("dataset_name"), rs.getLong("id"));
                }, conversationId, owner(userId), (long) (safePage - 1) * safeSize, safeSize);
    }

    public List<Entry> recent(String userId, long conversationId, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<Entry> entries = jdbc.query("""
                SELECT m.id,m.role,m.content,m.citations,m.create_time,m.trace_id,m.mode,m.dataset_name
                FROM kb_message m JOIN kb_conversation c ON c.id=m.conversation_id
                WHERE m.conversation_id=? AND c.user_id=?
                ORDER BY m.id DESC LIMIT ?
                """, (rs, n) -> {
                    String content = rs.getString("content");
                    return new Entry(rs.getString("role"), content,
                            citationsForHistory(rs.getString("citations"), content),
                            rs.getTimestamp("create_time").getTime(), rs.getString("trace_id"),
                            rs.getString("mode"), rs.getString("dataset_name"), rs.getLong("id"));
                }, conversationId, owner(userId), safeLimit);
        return entries.stream().sorted(java.util.Comparator.comparingLong(Entry::messageId)).toList();
    }

    @Override
    public void requireOwner(String userId, Long conversationId) {
        if (conversationId != null && !belongsTo(userId, conversationId)) throw notFound(conversationId);
    }

    public boolean belongsTo(String userId, long conversationId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_conversation WHERE id=? AND user_id=?",
                Integer.class, conversationId, owner(userId));
        return count != null && count > 0;
    }

    private void enqueueSummaryIfNeeded(String userId, long conversationId) {
        Integer count = jdbc.queryForObject("SELECT message_count FROM kb_conversation WHERE id=? AND user_id=?",
                Integer.class, conversationId, userId);
        if (count == null || count < SUMMARY_INTERVAL || count % SUMMARY_INTERVAL != 0) return;
        List<Long> coveredIds = jdbc.query("""
                SELECT m.id FROM kb_message m JOIN kb_conversation c ON c.id=m.conversation_id
                WHERE m.conversation_id=? AND c.user_id=? ORDER BY m.id LIMIT ?
                """, (rs, n) -> rs.getLong(1), conversationId, userId, Math.max(0, count - RECENT_MESSAGES));
        if (coveredIds.isEmpty()) return;
        jdbc.update("""
                INSERT INTO kb_context_summary_task(conversation_id,user_id,from_message_id,to_message_id,status,next_retry_at)
                VALUES (?,?,?,?, 'PENDING', NOW())
                ON CONFLICT (conversation_id,to_message_id) DO NOTHING
                """, conversationId, userId, coveredIds.get(0), coveredIds.get(coveredIds.size() - 1));
    }

    private String citationsForHistory(String stored, String content) {
        if (stored != null && !stored.isBlank() && !stored.equals("[]")) return stored;
        Matcher matcher = LEGACY_CITATION.matcher(content == null ? "" : content);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        while (matcher.find() && ids.size() < 1) ids.add(Long.parseLong(matcher.group(1)));
        if (ids.isEmpty()) return "[]";
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("SELECT id,content FROM kb_chunk WHERE id IN (" + placeholders + ")", rs -> {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                String excerpt = rs.getString("content").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
                values.add("{\"chunkId\":" + rs.getLong("id") + ",\"excerpt\":\""
                        + excerpt.substring(0, Math.min(160, excerpt.length())) + "\",\"score\":0}");
            }
            return "[" + String.join(",", values) + "]";
        }, ids.toArray());
    }

    private String owner(String userId) { return userId == null || userId.isBlank() ? "global" : userId; }
    private BusinessException notFound(long id) { return new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + id); }
}
