package com.modelrag.agent.intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed intent configuration; production has no process-local fallback. */
@Service
@Profile("!test")
public class IntentTreeService {
    private final JdbcTemplate jdbc;

    public IntentTreeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public IntentNode create(IntentNode node) {
        validate(node);
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_intent_node(dataset_id,parent_id,name,node_type,target_type,target_id,description,priority,enabled)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, node.datasetId(), node.parentId(), node.name(), node.nodeType(), node.targetType(),
                node.targetId(), node.description(), node.priority(), node.enabled());
        return get(node.datasetId(), id).orElseThrow();
    }

    public IntentNode update(long datasetId, long id, IntentNode node) {
        IntentNode normalized = new IntentNode(id, datasetId, node.parentId(), node.name(), node.nodeType(),
                node.targetType(), node.targetId(), node.description(), node.priority(), node.enabled());
        validate(normalized);
        int updated = jdbc.update("""
                UPDATE kb_intent_node SET parent_id=?,name=?,node_type=?,target_type=?,target_id=?,description=?,
                priority=?,enabled=?,update_time=NOW() WHERE dataset_id=? AND id=?
                """, normalized.parentId(), normalized.name(), normalized.nodeType(), normalized.targetType(),
                normalized.targetId(), normalized.description(), normalized.priority(), normalized.enabled(), datasetId, id);
        if (updated == 0) throw new IllegalArgumentException("意图不存在: " + id);
        return get(datasetId, id).orElseThrow();
    }

    public void delete(long datasetId, long id) {
        if (jdbc.update("DELETE FROM kb_intent_node WHERE dataset_id=? AND id=?", datasetId, id) == 0) {
            throw new IllegalArgumentException("意图不存在: " + id);
        }
    }

    public List<IntentNode> list(long datasetId) {
        return jdbc.query("""
                SELECT id,dataset_id,parent_id,name,node_type,target_type,target_id,description,priority,enabled
                FROM kb_intent_node WHERE dataset_id=? ORDER BY priority DESC,id
                """, (rs, n) -> new IntentNode(rs.getLong("id"), rs.getLong("dataset_id"),
                rs.getObject("parent_id", Long.class), rs.getString("name"), rs.getString("node_type"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("description"),
                rs.getInt("priority"), rs.getBoolean("enabled")), datasetId);
    }

    public Optional<IntentNode> match(long datasetId, String query) {
        String text = normalize(query);
        return list(datasetId).stream().filter(IntentNode::enabled)
                .filter(node -> terms(node).stream().anyMatch(text::contains)).findFirst();
    }

    private Optional<IntentNode> get(long datasetId, long id) {
        return list(datasetId).stream().filter(node -> node.id() != null && node.id() == id).findFirst();
    }

    private void validate(IntentNode node) {
        if (node.name() == null || node.name().isBlank()) throw new IllegalArgumentException("意图名称不能为空");
        if (!Set.of("RAG", "TOOL", "DIRECT").contains(node.targetType())) throw new IllegalArgumentException("不支持的意图目标");
        if ("TOOL".equals(node.targetType()) && (node.targetId() == null || node.targetId().isBlank())) {
            throw new IllegalArgumentException("TOOL 意图必须绑定工具");
        }
    }

    private List<String> terms(IntentNode node) {
        List<String> values = new ArrayList<>();
        add(values, node.name());
        if (node.description() != null) for (String term : node.description().split("[\\s,，、/|;；:：]+")) add(values, term);
        return values;
    }

    private void add(List<String> values, String term) {
        String normalized = normalize(term);
        if (!normalized.isBlank() && !values.contains(normalized)) values.add(normalized);
    }

    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s,，、/|;；:：]+", ""); }
}
