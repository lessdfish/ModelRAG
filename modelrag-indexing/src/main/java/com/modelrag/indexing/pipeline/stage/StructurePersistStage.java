package com.modelrag.indexing.pipeline.stage;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.DocumentNodeDraft;
import com.modelrag.knowledge.model.NodeEdgeDraft;
import com.modelrag.knowledge.parser.ParsedDocument;
import com.modelrag.knowledge.parser.ParsedEdge;
import com.modelrag.knowledge.parser.ParsedNode;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Persists a parser-local tree in one short transaction and reuses an existing version tree. */
@Service
public class StructurePersistStage {
    private final DocumentStructureRepository structures;
    private final DocumentVersionRepository versions;
    private final TransactionOperations transactions;

    public StructurePersistStage(DocumentStructureRepository structures, DocumentVersionRepository versions,
            TransactionOperations transactions) {
        this.structures = structures;
        this.versions = java.util.Objects.requireNonNull(versions, "文档版本仓储不能为空");
        this.transactions = transactions;
    }

    public StructureResult persist(long datasetId, long documentId, long documentVersionId,
            ParsedDocument parsed) {
        if (datasetId <= 0 || documentId <= 0 || documentVersionId <= 0 || parsed == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "结构持久化参数无效");
        }
        return transactions.execute(status -> persistInTransaction(datasetId, documentId, documentVersionId, parsed));
    }

    private StructureResult persistInTransaction(long datasetId, long documentId, long documentVersionId,
            ParsedDocument parsed) {
        versions.lockForStructure(documentVersionId);
        var existing = structures.findRootByVersion(documentVersionId);
        if (existing.isPresent()) {
            return new StructureResult(existing.get().id(), structures.countByVersion(documentVersionId), true, Map.of());
        }
        Map<String, Long> ids = new LinkedHashMap<>();
        List<ParsedNode> ordered = parsed.nodes().stream()
                .sorted(Comparator.comparingInt(ParsedNode::depth).thenComparingInt(ParsedNode::ordinal))
                .toList();
        for (ParsedNode node : ordered) {
            Long parentId = node.parentLocalId() == null ? null : ids.get(node.parentLocalId());
            if (node.parentLocalId() != null && parentId == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "解析节点父级未按拓扑顺序出现");
            }
            DocumentNode created = structures.createNode(new DocumentNodeDraft(datasetId, documentId,
                    documentVersionId, parentId, node.nodeType(), node.depth(), node.ordinal(), node.title(),
                    node.content(), contentHash(node.content()), node.pageFrom(), node.pageTo(), node.charStart(),
                    node.charEnd(), node.tokenCount(), node.searchable(), node.metadata()));
            ids.put(node.localId(), created.id());
        }
        for (ParsedEdge edge : parsed.edges()) {
            Long from = ids.get(edge.fromLocalId());
            Long to = ids.get(edge.toLocalId());
            if (from == null || to == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "解析边端点未持久化");
            }
            structures.createEdge(new NodeEdgeDraft(from, to, edge.edgeType(), edge.metadata()));
        }
        long rootId = ids.get(parsed.nodes().stream().filter(node -> node.parentLocalId() == null)
                .findFirst().orElseThrow().localId());
        return new StructureResult(rootId, ids.size(), false, Map.copyOf(ids));
    }

    private String contentHash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("文档节点内容哈希失败", error);
        }
    }

    public record StructureResult(long rootId, long nodeCount, boolean reused, Map<String, Long> nodeIds) {
        public StructureResult {
            nodeIds = nodeIds == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(nodeIds));
        }
    }
}
