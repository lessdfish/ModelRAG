package com.modelrag.knowledge.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitDraft;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for bounded V2 retrieval-unit persistence. */
@Repository
@Profile("!test")
public class JdbcRetrievalUnitRepository implements RetrievalUnitRepository {
    private static final int MAX_QUERY_LIMIT = 500;
    private static final int WRITE_BATCH_SIZE = 500;
    private static final String UNIT_COLUMNS = "id,dataset_id,document_id,document_version_id,node_id,"
            + "index_build_id,unit_type,ordinal,title_path,content,content_hash,token_count,metadata,create_time";
    private static final String ACTIVE_UNIT_COLUMNS = "u.id,u.dataset_id,u.document_id,u.document_version_id,u.node_id,"
            + "u.index_build_id,u.unit_type,u.ordinal,u.title_path,u.content,u.content_hash,u.token_count,u.metadata,u.create_time";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRetrievalUnitRepository(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    @Autowired
    public JdbcRetrievalUnitRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<RetrievalUnit> createBatch(List<RetrievalUnitDraft> units) {
        if (units == null || units.isEmpty()) return List.of();
        units.forEach(this::validateDraft);

        List<RetrievalUnit> created = new ArrayList<>(units.size());
        for (int start = 0; start < units.size(); start += WRITE_BATCH_SIZE) {
            int end = Math.min(units.size(), start + WRITE_BATCH_SIZE);
            for (RetrievalUnitDraft unit : units.subList(start, end)) {
                validateAggregate(unit);
                try {
                    Long id = jdbc.queryForObject("""
                            INSERT INTO kb_retrieval_unit(
                                dataset_id,document_id,document_version_id,node_id,index_build_id,
                                unit_type,ordinal,title_path,content,content_hash,token_count,metadata)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                            RETURNING id
                            """, Long.class, unit.datasetId(), unit.documentId(), unit.documentVersionId(),
                            unit.nodeId(), unit.indexBuildId(), unit.unitType().name(), unit.ordinal(),
                            unit.titlePath(), unit.content(), unit.contentHash(), unit.tokenCount(),
                            metadata(unit.metadata()));
                    created.add(findById(id).orElseThrow(
                            () -> new BusinessException(ErrorCode.INTERNAL, "检索单元创建后不可见")));
                } catch (DataIntegrityViolationException duplicate) {
                    throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                            "检索单元身份已存在或关联对象无效");
                }
            }
        }
        return List.copyOf(created);
    }

    @Override
    public Optional<RetrievalUnit> findById(long unitId) {
        if (unitId <= 0) return Optional.empty();
        return query("SELECT " + UNIT_COLUMNS + " FROM kb_retrieval_unit WHERE id=?", unitId)
                .stream().findFirst();
    }

    @Override
    public List<RetrievalUnit> findByBuild(long buildId, int offset, int limit) {
        if (buildId <= 0) return List.of();
        int boundedLimit = boundedLimit(limit);
        if (boundedLimit == 0) return List.of();
        return query("SELECT " + UNIT_COLUMNS + " FROM kb_retrieval_unit "
                + "WHERE index_build_id=? ORDER BY id LIMIT ? OFFSET ?",
                buildId, boundedLimit, Math.max(0, offset));
    }

    @Override
    public List<RetrievalUnit> findByNode(long nodeId, int offset, int limit) {
        if (nodeId <= 0) return List.of();
        int boundedLimit = boundedLimit(limit);
        if (boundedLimit == 0) return List.of();
        return query("SELECT " + UNIT_COLUMNS + " FROM kb_retrieval_unit "
                + "WHERE node_id=? ORDER BY id LIMIT ? OFFSET ?",
                nodeId, boundedLimit, Math.max(0, offset));
    }

    @Override
    public List<RetrievalUnit> findActiveByIds(long datasetId, Collection<Long> unitIds) {
        if (datasetId <= 0 || unitIds == null || unitIds.isEmpty()) return List.of();
        if (unitIds.size() > MAX_QUERY_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION, "一次最多读取 500 个检索单元");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long unitId : unitIds) {
            if (unitId == null || unitId <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION, "检索单元 ID 无效");
            }
            ids.add(unitId);
        }
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(datasetId);
        args.addAll(ids);
        return query("""
                SELECT %s FROM kb_retrieval_unit u
                JOIN kb_document d ON d.id=u.document_id
                    AND d.active_version_id=u.document_version_id
                    AND d.active_index_build_id=u.index_build_id
                JOIN kb_dataset ds ON ds.id=u.dataset_id AND ds.id=d.dataset_id
                WHERE u.dataset_id=? AND u.id IN (%s)
                    AND d.delete_time IS NULL AND ds.delete_time IS NULL
                ORDER BY u.id
                """.formatted(ACTIVE_UNIT_COLUMNS, placeholders),
                args.toArray());
    }

    @Override
    public long countByBuild(long buildId) {
        if (buildId <= 0) return 0;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_retrieval_unit WHERE index_build_id=?",
                Long.class, buildId);
        return count == null ? 0 : count;
    }

    private void validateDraft(RetrievalUnitDraft unit) {
        if (unit == null || unit.unitType() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元类型不能为空");
        }
        if (unit.datasetId() <= 0 || unit.documentId() <= 0 || unit.documentVersionId() <= 0
                || unit.nodeId() <= 0 || unit.indexBuildId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元归属无效");
        }
        if (unit.ordinal() < 0 || unit.tokenCount() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元序号或 token 数无效");
        }
        if (unit.content() == null || unit.content().isBlank()
                || unit.contentHash() == null || unit.contentHash().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元内容或内容哈希不能为空");
        }
    }

    private void validateAggregate(RetrievalUnitDraft unit) {
        List<AggregateRow> matches = jdbc.query("""
                SELECT n.dataset_id AS node_dataset_id,
                       n.document_id AS node_document_id,
                       n.document_version_id AS node_version_id,
                       b.dataset_id AS build_dataset_id,
                       b.document_id AS build_document_id,
                       b.document_version_id AS build_version_id,
                       b.state AS build_state
                FROM kb_document_node n
                JOIN kb_index_build b ON b.id=?
                WHERE n.id=?
                """, (rs, row) -> new AggregateRow(rs.getLong("node_dataset_id"),
                rs.getLong("node_document_id"), rs.getLong("node_version_id"),
                rs.getLong("build_dataset_id"), rs.getLong("build_document_id"),
                rs.getLong("build_version_id"), rs.getString("build_state")),
                unit.indexBuildId(), unit.nodeId());
        if (matches.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元节点或构建不存在");
        }
        AggregateRow row = matches.get(0);
        if (row.nodeDatasetId != unit.datasetId() || row.nodeDocumentId != unit.documentId()
                || row.nodeVersionId != unit.documentVersionId()
                || row.buildDatasetId != unit.datasetId() || row.buildDocumentId != unit.documentId()
                || row.buildVersionId != unit.documentVersionId()) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元、节点和构建归属不一致");
        }
        if (!"UNIT_BUILDING".equals(row.buildState)) {
            throw new BusinessException(ErrorCode.VALIDATION, "只有 UNIT_BUILDING 构建可以写入检索单元");
        }
    }

    private int boundedLimit(int limit) {
        return Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
    }

    private List<RetrievalUnit> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> unit(rs), args);
    }

    private RetrievalUnit unit(ResultSet rs) throws java.sql.SQLException {
        Timestamp createTime = rs.getTimestamp("create_time");
        return new RetrievalUnit(rs.getLong("id"), rs.getLong("dataset_id"), rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getLong("node_id"), rs.getLong("index_build_id"),
                RetrievalUnitType.valueOf(rs.getString("unit_type")), rs.getInt("ordinal"),
                rs.getString("title_path"), rs.getString("content"), rs.getString("content_hash"),
                rs.getInt("token_count"), metadata(rs.getString("metadata")),
                createTime == null ? null : createTime.toInstant());
    }

    private String metadata(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元元数据无法序列化");
        }
    }

    private Map<String, Object> metadata(String value) {
        try {
            return json.readValue(value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("检索单元元数据无法解析", error);
        }
    }

    private record AggregateRow(long nodeDatasetId, long nodeDocumentId, long nodeVersionId,
            long buildDatasetId, long buildDocumentId, long buildVersionId, String buildState) { }
}
