package com.modelrag.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.api.UserModelProvider;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.exception.SafeErrorSummary;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Claims and generates bounded rolling summaries without blocking the answer request. */
@Service
@Profile("!test")
public class ConversationSummaryWorker {
    private static final int RECENT_MESSAGES = 8;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<UserModelProvider> models;
    private final TokenUsageTracker tokens;
    private final ObjectMapper json;
    private final String configuredModel;

    public ConversationSummaryWorker(JdbcTemplate jdbc, ObjectProvider<UserModelProvider> models,
            TokenUsageTracker tokens, ObjectMapper json,
            @Value("${modelrag.ollama.chat-model:configured-user-model}") String configuredModel) {
        this.jdbc = jdbc;
        this.models = models;
        this.tokens = tokens;
        this.json = json;
        this.configuredModel = configuredModel;
    }

    @Scheduled(fixedDelayString = "${modelrag.summary.poll-ms:1000}")
    public void processDueTasks() {
        for (int i = 0; i < 2; i++) claim().ifPresent(this::process);
    }

    private Optional<Task> claim() {
        return jdbc.query("""
                UPDATE kb_context_summary_task SET status='PROCESSING',attempt_count=attempt_count+1,
                    lease_until=NOW()+INTERVAL '2 minutes',update_time=NOW()
                WHERE id=(
                    SELECT id FROM kb_context_summary_task
                    WHERE (status IN ('PENDING','FAILED') AND next_retry_at<=NOW())
                       OR (status='PROCESSING' AND lease_until<NOW())
                    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                RETURNING id,conversation_id,user_id,from_message_id,to_message_id,attempt_count
                """, (rs, n) -> new Task(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getLong(4), rs.getLong(5), rs.getInt(6))).stream().findFirst();
    }

    private void process(Task task) {
        try {
            UserModelProvider model = models.getIfAvailable();
            if (model == null) throw new IllegalStateException("没有可用的用户模型配置");
            PreviousSummary previous = previousSummary(task);
            String source = messages(task, Math.max(task.fromMessageId(), previous.toMessageId() + 1));
            String prompt = prompt(previous.content(), source);
            String generated = model.generate(task.userId(), prompt);
            String summary = structured(generated);
            Long datasetId = jdbc.queryForObject("SELECT dataset_id FROM kb_conversation WHERE id=? AND user_id=?",
                    Long.class, task.conversationId(), task.userId());
            jdbc.update("""
                    INSERT INTO kb_context_summary(conversation_id,summary_type,from_message_id,to_message_id,
                        summary,token_count,prompt_version,status,model_name,last_error,token_usage)
                    SELECT ?, 'HISTORY', ?, ?, ?, ?, 'conversation-summary-v2', 'READY', ?, NULL, ?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM kb_context_summary WHERE conversation_id=? AND to_message_id>? AND status='READY'
                    )
                    ON CONFLICT (conversation_id,to_message_id) WHERE to_message_id > 0 DO UPDATE SET
                        summary=EXCLUDED.summary,token_count=EXCLUDED.token_count,prompt_version=EXCLUDED.prompt_version,
                        status='READY',model_name=EXCLUDED.model_name,last_error=NULL,token_usage=EXCLUDED.token_usage,create_time=NOW()
                    """, task.conversationId(), task.fromMessageId(), task.toMessageId(), summary,
                    Math.max(1, generated.length() / 4), model.selectedModel(task.userId()),
                    Math.max(1, generated.length() / 4), task.conversationId(), task.toMessageId());
            if (datasetId != null) tokens.recordSummary(datasetId, prompt, generated);
            jdbc.update("UPDATE kb_context_summary_task SET status='DONE',lease_until=NULL,last_error=NULL,update_time=NOW() WHERE id=?", task.id());
        } catch (RuntimeException error) {
            long backoff = Math.min(3600, 10L * (1L << Math.min(8, Math.max(0, task.attemptCount() - 1))));
            jdbc.update("""
                    UPDATE kb_context_summary_task SET status='FAILED',lease_until=NULL,last_error=?,
                        next_retry_at=NOW()+(? * INTERVAL '1 second'),update_time=NOW() WHERE id=?
                    """, SafeErrorSummary.of(error), backoff, task.id());
        }
    }

    private String messages(Task task, long fromMessageId) {
        return jdbc.query("""
                SELECT role,content FROM kb_message
                WHERE conversation_id=? AND id BETWEEN ? AND ? ORDER BY id
                """, (rs, n) -> rs.getString(1) + ": " + rs.getString(2), task.conversationId(),
                fromMessageId, task.toMessageId()).stream().reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private PreviousSummary previousSummary(Task task) {
        return jdbc.query("""
                SELECT summary,to_message_id FROM kb_context_summary
                WHERE conversation_id=? AND to_message_id<? AND status='READY'
                ORDER BY to_message_id DESC LIMIT 1
                """, (rs, n) -> new PreviousSummary(rs.getString(1), rs.getLong(2)), task.conversationId(),
                task.toMessageId()).stream().findFirst().orElse(new PreviousSummary("", 0));
    }

    private String prompt(String previous, String messages) {
        return "你是会话摘要器。只输出 JSON，不要解释。保留字段：userGoal、confirmedFacts、unresolvedQuestions、keyConstraints、citations、toolResults。"
                + "摘要是辅助上下文，不得补造事实。最近摘要：\n" + limit(previous, 6000)
                + "\n待覆盖消息：\n" + limit(messages, 12000);
    }

    private String structured(String generated) {
        String text = generated == null ? "" : generated.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try {
            JsonNode node = json.readTree(text);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("摘要模型未返回 JSON 对象");
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("userGoal", node.path("userGoal").asText(""));
            normalized.put("confirmedFacts", array(node, "confirmedFacts"));
            normalized.put("unresolvedQuestions", array(node, "unresolvedQuestions"));
            normalized.put("keyConstraints", array(node, "keyConstraints"));
            normalized.put("citations", array(node, "citations"));
            normalized.put("toolResults", array(node, "toolResults"));
            return json.writeValueAsString(normalized);
        } catch (Exception error) {
            throw new IllegalStateException("摘要结构化失败", error);
        }
    }

    private List<String> array(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) return List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (JsonNode value : values) {
            String text = value.asText("").trim();
            if (!text.isBlank()) result.add(limit(text, 1000));
        }
        return List.copyOf(result);
    }

    private String limit(String value, int max) { String text = value == null ? "" : value; return text.length() <= max ? text : text.substring(0, max); }
    private record Task(long id, long conversationId, String userId, long fromMessageId, long toMessageId, int attemptCount) {}
    private record PreviousSummary(String content, long toMessageId) {}
}
