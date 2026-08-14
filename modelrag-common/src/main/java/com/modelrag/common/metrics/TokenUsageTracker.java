package com.modelrag.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class TokenUsageTracker {
    public record Usage(long promptTokens, long completionTokens, long embeddingTokens, long rerankCalls,
                        long summaryTokens, long summaryCalls, long totalTokens, double estimatedCost,
                        boolean overBudget) {
    }

    private static final long DATASET_BUDGET = 100_000;
    private final MeterRegistry metrics;
    private final JdbcTemplate jdbc;

    public TokenUsageTracker(MeterRegistry metrics, JdbcTemplate jdbc) {
        this.metrics = metrics;
        this.jdbc = jdbc;
    }

    public Usage record(long datasetId, String prompt, String completion) {
        long in = estimate(prompt), out = estimate(completion);
        add(datasetId, in, out, 0, 0);
        metrics.counter("modelrag.token.usage", "type", "prompt").increment(in);
        metrics.counter("modelrag.token.usage", "type", "completion").increment(out);
        return usage(datasetId);
    }

    public void recordEmbedding(long datasetId, String text) {
        long count = estimate(text);
        add(datasetId, 0, 0, count, 0);
        metrics.counter("modelrag.token.usage", "type", "embedding").increment(count);
    }

    public void recordRerank(long datasetId) {
        add(datasetId, 0, 0, 0, 1);
        metrics.counter("modelrag.rerank.calls").increment();
    }

    public Usage usage(long datasetId) {
        return jdbc.query("SELECT prompt_tokens,completion_tokens,embedding_tokens,rerank_calls,summary_tokens,summary_calls,estimated_cost FROM kb_token_usage_daily WHERE dataset_id=? AND usage_date=CURRENT_DATE", rs -> rs.next() ? usage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getDouble(7)) : usage(0, 0, 0, 0, 0, 0, 0), datasetId);
    }

    public void recordSummary(long datasetId, String prompt, String completion) {
        long count = estimate(prompt) + estimate(completion);
        jdbc.update("INSERT INTO kb_token_usage_daily(usage_date,dataset_id,summary_tokens,summary_calls,estimated_cost) VALUES (CURRENT_DATE,?,?,?,0) ON CONFLICT(usage_date,dataset_id) DO UPDATE SET summary_tokens=kb_token_usage_daily.summary_tokens+EXCLUDED.summary_tokens,summary_calls=kb_token_usage_daily.summary_calls+1,update_time=NOW()", datasetId, count, 1);
        metrics.counter("modelrag.token.usage", "type", "summary").increment(count);
    }

    private void add(long datasetId, long prompt, long completion, long embedding, long rerank) {
        persist(datasetId, prompt, completion, embedding, rerank);
    }

    private Usage usage(long prompt, long completion, long embedding, long rerank, long summary, long summaryCalls, double cost) {
        long tokens = prompt + completion + embedding;
        return new Usage(prompt, completion, embedding, rerank, summary, summaryCalls, tokens, cost, tokens > DATASET_BUDGET);
    }

    private void persist(long datasetId, long prompt, long completion, long embedding, long rerank) {
        jdbc.update("INSERT INTO kb_token_usage_daily(usage_date,dataset_id,prompt_tokens,completion_tokens,embedding_tokens,rerank_calls,estimated_cost) VALUES (CURRENT_DATE,?,?,?,?,?,0) ON CONFLICT(usage_date,dataset_id) DO UPDATE SET prompt_tokens=kb_token_usage_daily.prompt_tokens+EXCLUDED.prompt_tokens,completion_tokens=kb_token_usage_daily.completion_tokens+EXCLUDED.completion_tokens,embedding_tokens=kb_token_usage_daily.embedding_tokens+EXCLUDED.embedding_tokens,rerank_calls=kb_token_usage_daily.rerank_calls+EXCLUDED.rerank_calls,update_time=NOW()", datasetId, prompt, completion, embedding, rerank);
    }

    private long estimate(String text) {
        return text == null ? 0 : Math.max(1, (text.length() + 3) / 4);
    }
}
