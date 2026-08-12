package com.modelrag.qa.feedback;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QaFeedbackService {
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final AtomicLong ids = new AtomicLong();
    private final List<Map<String, Object>> local = new CopyOnWriteArrayList<>();

    public QaFeedbackService(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> submit(long datasetId, String traceId, String userId, String rating, String comment) {
        String normalizedRating = normalizeRating(rating);
        String safeTraceId = requireTrace(traceId);
        String safeComment = comment == null ? "" : comment.trim();
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            Long id = db.queryForObject("""
                    INSERT INTO kb_feedback(trace_id,dataset_id,user_id,rating,comment)
                    VALUES (?,?,?,?,?)
                    RETURNING id
                    """, Long.class, safeTraceId, datasetId, userId, normalizedRating, safeComment);
            return row(id == null ? 0 : id, safeTraceId, datasetId, userId, normalizedRating, safeComment, Instant.now());
        } catch (Exception ignored) {
        }
        Map<String, Object> row = row(ids.incrementAndGet(), safeTraceId, datasetId, userId, normalizedRating, safeComment, Instant.now());
        local.add(0, row);
        return row;
    }

    public List<Map<String, Object>> list() {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            return db.query("""
                    SELECT id,trace_id,dataset_id,user_id,rating,comment,create_time
                    FROM kb_feedback
                    ORDER BY id DESC
                    LIMIT 200
                    """, (rs, n) -> row(rs.getLong("id"), rs.getString("trace_id"), rs.getLong("dataset_id"),
                    rs.getString("user_id"), rs.getString("rating"), rs.getString("comment"),
                    rs.getTimestamp("create_time").toInstant()));
        } catch (Exception ignored) {
        }
        return new ArrayList<>(local);
    }

    private String requireTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) throw new BusinessException(ErrorCode.VALIDATION, "traceId 不能为空");
        return traceId.trim();
    }

    private String normalizeRating(String rating) {
        String value = Objects.toString(rating, "").trim().toUpperCase();
        if (!"LIKE".equals(value) && !"DISLIKE".equals(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, "rating 只能是 LIKE 或 DISLIKE");
        }
        return value;
    }

    private Map<String, Object> row(long id, String traceId, long datasetId, String userId, String rating, String comment, Instant createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("traceId", traceId);
        row.put("datasetId", datasetId);
        row.put("userId", userId);
        row.put("rating", rating);
        row.put("comment", comment == null ? "" : comment);
        row.put("createdAt", Timestamp.from(createdAt).toInstant().toString());
        return row;
    }
}
