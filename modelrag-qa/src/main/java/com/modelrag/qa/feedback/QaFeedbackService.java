package com.modelrag.qa.feedback;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class QaFeedbackService {
    private final JdbcTemplate jdbc;

    public QaFeedbackService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public FeedbackView submit(long datasetId, String traceId, String userId, String rating, String comment) {
        String normalizedRating = normalizeRating(rating);
        String safeTraceId = requireTrace(traceId);
        String safeComment = comment == null ? "" : comment.trim();
        Long id = jdbc.queryForObject("""
                    INSERT INTO kb_feedback(trace_id,dataset_id,user_id,rating,comment)
                    VALUES (?,?,?,?,?)
                    RETURNING id
                    """, Long.class, safeTraceId, datasetId, userId, normalizedRating, safeComment);
        return new FeedbackView(id == null ? 0 : id, safeTraceId, datasetId, userId, normalizedRating,
                safeComment, Instant.now());
    }

    public List<FeedbackView> list() {
        return jdbc.query("""
                    SELECT id,trace_id,dataset_id,user_id,rating,comment,create_time
                    FROM kb_feedback
                    ORDER BY id DESC
                    LIMIT 200
                    """, (rs, n) -> row(rs.getLong("id"), rs.getString("trace_id"), rs.getLong("dataset_id"),
                    rs.getString("user_id"), rs.getString("rating"), rs.getString("comment"),
                    rs.getTimestamp("create_time").toInstant()));
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

    private FeedbackView row(long id, String traceId, long datasetId, String userId, String rating,
            String comment, Instant createdAt) {
        return new FeedbackView(id, traceId, datasetId, userId, rating, comment == null ? "" : comment, createdAt);
    }
}
