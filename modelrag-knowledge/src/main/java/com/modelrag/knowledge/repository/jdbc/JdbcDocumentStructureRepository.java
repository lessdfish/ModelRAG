package com.modelrag.knowledge.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.DocumentNodeDraft;
import com.modelrag.knowledge.model.NodeEdge;
import com.modelrag.knowledge.model.NodeEdgeDraft;
import com.modelrag.knowledge.model.NodeEdgeType;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for immutable document-structure nodes. */
@Repository
@Profile("!test")
public class JdbcDocumentStructureRepository implements DocumentStructureRepository {
    private static final int MAX_QUERY_LIMIT = 100;
    private static final int MAX_ANCESTOR_DEPTH = 32;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcDocumentStructureRepository(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JdbcDocumentStructureRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<DocumentNode> findRootByVersion(long documentVersionId) {
        if (documentVersionId <= 0) return Optional.empty();
        return query("SELECT * FROM kb_document_node WHERE document_version_id=? AND parent_id IS NULL",
                documentVersionId).stream().findFirst();
    }

    @Override
    public long countByVersion(long documentVersionId) {
        if (documentVersionId <= 0) return 0;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_node WHERE document_version_id=?",
                Long.class, documentVersionId);
        return count == null ? 0 : count;
    }

    @Override
    public List<DocumentNode> findByVersion(long documentVersionId, int offset, int limit) {
        if (documentVersionId <= 0) return List.of();
        int boundedLimit = Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return query("SELECT * FROM kb_document_node WHERE document_version_id=? "
                + "ORDER BY depth ASC,ordinal ASC,id ASC LIMIT ? OFFSET ?",
                documentVersionId, boundedLimit, Math.max(0, offset));
    }

    @Override
    public DocumentNode createNode(DocumentNodeDraft node) {
        validateDraft(node);
        validateDocumentVersion(node);
        validateParent(node);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO kb_document_node(dataset_id,document_id,document_version_id,parent_id,node_type,
                        depth,ordinal,title,content,content_hash,page_from,page_to,char_start,char_end,token_count,
                        searchable,metadata)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,CAST(? AS jsonb))
                    RETURNING id
                    """, Long.class, node.datasetId(), node.documentId(), node.documentVersionId(), node.parentId(),
                    node.nodeType().name(), node.depth(), node.ordinal(), node.title(), node.content(), node.contentHash(),
                    node.pageFrom(), node.pageTo(), node.charStart(), node.charEnd(), node.tokenCount(), node.searchable(),
                    metadata(node.metadata()));
            return findById(id).orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL, "节点创建后不可见"));
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "文档节点根或兄弟序号已存在");
        }
    }

    @Override
    public Optional<DocumentNode> findById(long nodeId) {
        return query("SELECT * FROM kb_document_node WHERE id=?", nodeId).stream().findFirst();
    }

    @Override
    public Optional<DocumentNode> findActiveById(long nodeId) {
        return query("""
                SELECT n.* FROM kb_document_node n
                JOIN kb_document d ON d.id=n.document_id
                    AND d.active_version_id=n.document_version_id
                JOIN kb_dataset ds ON ds.id=n.dataset_id AND ds.id=d.dataset_id
                WHERE n.id=? AND d.delete_time IS NULL AND ds.delete_time IS NULL
                """, nodeId).stream().findFirst();
    }

    @Override
    public List<DocumentNode> findActiveByIds(long datasetId, Collection<Long> nodeIds) {
        List<Long> requested = nodeIds == null ? List.of() : nodeIds.stream()
                .filter(id -> id != null && id > 0).distinct().toList();
        if (datasetId <= 0 || requested.isEmpty()) return List.of();
        if (requested.size() > MAX_QUERY_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION, "节点批量读取范围过大");
        }
        String placeholders = requested.stream().map(ignored -> "?")
                .collect(java.util.stream.Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(datasetId);
        args.addAll(requested);
        return query("""
                SELECT n.* FROM kb_document_node n
                JOIN kb_document d ON d.id=n.document_id
                    AND d.active_version_id=n.document_version_id
                JOIN kb_dataset ds ON ds.id=n.dataset_id AND ds.id=d.dataset_id
                WHERE n.dataset_id=? AND n.id IN (%s)
                    AND d.delete_time IS NULL AND ds.delete_time IS NULL
                ORDER BY n.id
                """.formatted(placeholders), args.toArray());
    }

    @Override
    public List<DocumentNode> findActiveChildren(long parentNodeId, int offset, int limit) {
        int boundedOffset = Math.max(0, offset);
        int boundedLimit = Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return query("""
                SELECT child.* FROM kb_document_node child
                JOIN kb_document d ON d.id=child.document_id
                    AND d.active_version_id=child.document_version_id
                JOIN kb_dataset ds ON ds.id=child.dataset_id AND ds.id=d.dataset_id
                WHERE child.parent_id=? AND d.delete_time IS NULL AND ds.delete_time IS NULL
                ORDER BY child.ordinal ASC
                LIMIT ? OFFSET ?
                """, parentNodeId, boundedLimit, boundedOffset);
    }

    @Override
    public List<DocumentNode> findActivePrevious(long nodeId, int limit) {
        int boundedLimit = Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return query("""
                SELECT windowed.* FROM (
                    SELECT sibling.* FROM kb_document_node center
                    JOIN kb_document d ON d.id=center.document_id
                        AND d.active_version_id=center.document_version_id
                    JOIN kb_dataset ds ON ds.id=center.dataset_id AND ds.id=d.dataset_id
                    JOIN kb_document_node sibling
                        ON sibling.document_id=center.document_id
                        AND sibling.document_version_id=center.document_version_id
                        AND (sibling.parent_id=center.parent_id
                             OR (sibling.parent_id IS NULL AND center.parent_id IS NULL))
                    WHERE center.id=? AND d.delete_time IS NULL AND ds.delete_time IS NULL
                        AND sibling.ordinal < center.ordinal
                    ORDER BY sibling.ordinal DESC
                    LIMIT ?
                ) windowed
                ORDER BY windowed.ordinal ASC
                """, nodeId, boundedLimit);
    }

    @Override
    public List<DocumentNode> findActiveNext(long nodeId, int limit) {
        int boundedLimit = Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return query("""
                SELECT sibling.* FROM kb_document_node center
                JOIN kb_document d ON d.id=center.document_id
                    AND d.active_version_id=center.document_version_id
                JOIN kb_dataset ds ON ds.id=center.dataset_id AND ds.id=d.dataset_id
                JOIN kb_document_node sibling
                    ON sibling.document_id=center.document_id
                    AND sibling.document_version_id=center.document_version_id
                    AND (sibling.parent_id=center.parent_id
                         OR (sibling.parent_id IS NULL AND center.parent_id IS NULL))
                WHERE center.id=? AND d.delete_time IS NULL AND ds.delete_time IS NULL
                    AND sibling.ordinal > center.ordinal
                ORDER BY sibling.ordinal ASC
                LIMIT ?
                """, nodeId, boundedLimit);
    }

    @Override
    public List<DocumentNode> findActiveAncestors(long nodeId, int maxDepth) {
        int boundedDepth = Math.min(MAX_ANCESTOR_DEPTH, Math.max(0, maxDepth));
        if (boundedDepth == 0) return List.of();
        return query("""
                WITH RECURSIVE ancestor_chain AS (
                    SELECT parent.*, 1 AS distance
                    FROM kb_document_node child
                    JOIN kb_document d ON d.id=child.document_id
                        AND d.active_version_id=child.document_version_id
                    JOIN kb_dataset ds ON ds.id=child.dataset_id AND ds.id=d.dataset_id
                    JOIN kb_document_node parent ON parent.id=child.parent_id
                    WHERE child.id=? AND d.delete_time IS NULL AND ds.delete_time IS NULL
                    UNION ALL
                    SELECT parent.*, ancestor_chain.distance + 1
                    FROM ancestor_chain
                    JOIN kb_document_node parent ON parent.id=ancestor_chain.parent_id
                    WHERE ancestor_chain.distance < ?
                )
                SELECT id,dataset_id,document_id,document_version_id,parent_id,node_type,depth,ordinal,title,
                       content,content_hash,page_from,page_to,char_start,char_end,token_count,searchable,metadata,create_time
                FROM ancestor_chain
                ORDER BY distance DESC
                """, nodeId, boundedDepth);
    }

    @Override
    public NodeEdge createEdge(NodeEdgeDraft edge) {
        if (edge == null || edge.edgeType() == null || edge.fromNodeId() <= 0 || edge.toNodeId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "节点边参数无效");
        }
        if (jdbc.query("SELECT id FROM kb_document_node WHERE id IN (?,?)",
                (rs, n) -> rs.getLong(1), edge.fromNodeId(), edge.toNodeId()).size() != 2) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "节点边端点不存在");
        }
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO kb_node_edge(from_node_id,to_node_id,edge_type,metadata)
                    VALUES (?,?,?,CAST(? AS jsonb)) RETURNING id
                    """, Long.class, edge.fromNodeId(), edge.toNodeId(), edge.edgeType().name(),
                    metadata(edge.metadata()));
            return jdbc.query("SELECT * FROM kb_node_edge WHERE id=?", (rs, n) -> edge(rs), id).stream()
                    .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL, "节点边创建后不可见"));
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "节点边已存在");
        }
    }

    @Override
    public List<NodeEdge> findOutgoingEdges(long nodeId, Set<NodeEdgeType> types, int limit) {
        int boundedLimit = Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
        Set<NodeEdgeType> requested = types == null ? Set.of()
                : types.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (boundedLimit == 0 || requested.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(requested.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(nodeId);
        requested.forEach(type -> args.add(type.name()));
        args.add(boundedLimit);
        String sql = """
                SELECT e.* FROM kb_node_edge e
                JOIN kb_document_node from_node ON from_node.id=e.from_node_id
                JOIN kb_document from_doc ON from_doc.id=from_node.document_id
                    AND from_doc.active_version_id=from_node.document_version_id
                JOIN kb_dataset from_ds ON from_ds.id=from_node.dataset_id AND from_ds.id=from_doc.dataset_id
                JOIN kb_document_node to_node ON to_node.id=e.to_node_id
                JOIN kb_document to_doc ON to_doc.id=to_node.document_id
                    AND to_doc.active_version_id=to_node.document_version_id
                JOIN kb_dataset to_ds ON to_ds.id=to_node.dataset_id AND to_ds.id=to_doc.dataset_id
                WHERE e.from_node_id=? AND e.edge_type IN (%s)
                    AND from_doc.delete_time IS NULL AND from_ds.delete_time IS NULL
                    AND to_doc.delete_time IS NULL AND to_ds.delete_time IS NULL
                ORDER BY e.id ASC LIMIT ?
                """.formatted(placeholders);
        return jdbc.query(sql, (rs, n) -> edge(rs), args.toArray());
    }

    @Override
    public List<DocumentNode> findReferenceTargets(long nodeId, int limit) {
        int boundedLimit = Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return query("""
                SELECT target.* FROM kb_node_edge e
                JOIN kb_document_node source ON source.id=e.from_node_id
                JOIN kb_document source_doc ON source_doc.id=source.document_id
                    AND source_doc.active_version_id=source.document_version_id
                JOIN kb_dataset source_ds ON source_ds.id=source.dataset_id AND source_ds.id=source_doc.dataset_id
                JOIN kb_document_node target ON target.id=e.to_node_id
                JOIN kb_document target_doc ON target_doc.id=target.document_id
                    AND target_doc.active_version_id=target.document_version_id
                JOIN kb_dataset target_ds ON target_ds.id=target.dataset_id AND target_ds.id=target_doc.dataset_id
                WHERE e.from_node_id=? AND e.edge_type='REFERENCE'
                    AND source_doc.delete_time IS NULL AND source_ds.delete_time IS NULL
                    AND target_doc.delete_time IS NULL AND target_ds.delete_time IS NULL
                ORDER BY e.id ASC LIMIT ?
                """, nodeId, boundedLimit);
    }

    private void validateDraft(DocumentNodeDraft node) {
        if (node == null || node.nodeType() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档节点类型不能为空");
        }
        if (node.datasetId() <= 0 || node.documentId() <= 0 || node.documentVersionId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档节点归属无效");
        }
        if (node.depth() < 0 || node.ordinal() < 0 || node.tokenCount() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档节点位置或 token 数无效");
        }
        if ((node.pageFrom() == null) != (node.pageTo() == null)
                || (node.pageFrom() != null && (node.pageFrom() <= 0 || node.pageTo() < node.pageFrom()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档节点页码范围无效");
        }
        if ((node.charStart() == null) != (node.charEnd() == null)
                || (node.charStart() != null && (node.charStart() < 0 || node.charEnd() < node.charStart()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档节点字符范围无效");
        }
        if (node.parentId() == null && node.depth() != 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "根节点深度必须为 0");
        }
    }

    private void validateDocumentVersion(DocumentNodeDraft node) {
        List<Long> matches = jdbc.query("""
                SELECT v.id FROM kb_document_version v
                JOIN kb_document d ON d.id=v.document_id AND d.id=?
                JOIN kb_dataset ds ON ds.id=d.dataset_id AND ds.id=?
                WHERE v.id=? AND d.delete_time IS NULL AND ds.delete_time IS NULL
                """, (rs, n) -> rs.getLong(1), node.documentId(), node.datasetId(), node.documentVersionId());
        if (matches.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档版本不属于该文档或知识库");
        }
    }

    private void validateParent(DocumentNodeDraft node) {
        if (node.parentId() == null) return;
        List<ParentRow> parents = jdbc.query("""
                SELECT dataset_id,document_id,document_version_id,depth
                FROM kb_document_node WHERE id=?
                """, (rs, n) -> new ParentRow(rs.getLong("dataset_id"), rs.getLong("document_id"),
                        rs.getLong("document_version_id"), rs.getInt("depth")), node.parentId());
        if (parents.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "父节点不存在");
        ParentRow parent = parents.get(0);
        if (parent.datasetId != node.datasetId() || parent.documentId != node.documentId()
                || parent.documentVersionId != node.documentVersionId()) {
            throw new BusinessException(ErrorCode.VALIDATION, "父节点必须属于同一文档版本");
        }
        if (node.depth() != parent.depth + 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "子节点深度必须为父节点深度加一");
        }
    }

    private List<DocumentNode> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> node(rs), args);
    }

    private DocumentNode node(ResultSet rs) throws java.sql.SQLException {
        Timestamp createTime = rs.getTimestamp("create_time");
        return new DocumentNode(rs.getLong("id"), rs.getLong("dataset_id"), rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getObject("parent_id", Long.class),
                NodeType.valueOf(rs.getString("node_type")), rs.getInt("depth"), rs.getInt("ordinal"),
                rs.getString("title"), rs.getString("content"), rs.getString("content_hash"),
                rs.getObject("page_from", Integer.class), rs.getObject("page_to", Integer.class),
                rs.getObject("char_start", Long.class), rs.getObject("char_end", Long.class),
                rs.getInt("token_count"), rs.getBoolean("searchable"), metadata(rs.getString("metadata")),
                createTime == null ? null : createTime.toInstant());
    }

    private NodeEdge edge(ResultSet rs) throws java.sql.SQLException {
        Timestamp createTime = rs.getTimestamp("create_time");
        return new NodeEdge(rs.getLong("id"), rs.getLong("from_node_id"), rs.getLong("to_node_id"),
                NodeEdgeType.valueOf(rs.getString("edge_type")), metadata(rs.getString("metadata")),
                createTime == null ? null : createTime.toInstant());
    }

    private String metadata(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档节点元数据无法序列化");
        }
    }

    private Map<String, Object> metadata(String value) {
        try {
            return json.readValue(value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("文档节点元数据无法解析", error);
        }
    }

    private record ParentRow(long datasetId, long documentId, long documentVersionId, int depth) { }
}
