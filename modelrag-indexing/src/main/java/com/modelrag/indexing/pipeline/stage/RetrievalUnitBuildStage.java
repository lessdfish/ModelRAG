package com.modelrag.indexing.pipeline.stage;

import com.modelrag.indexing.pipeline.IndexBuildContext;
import com.modelrag.indexing.pipeline.RetrievalUnitPolicy;
import com.modelrag.knowledge.model.DocumentNode;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.model.RetrievalUnitDraft;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.DocumentStructureRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.knowledge.splitter.RecursiveCharSplitter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds bounded retrieval projections from persisted nodes without redefining document structure. */
@Service
public class RetrievalUnitBuildStage {
    private static final int PAGE_SIZE = 500;
    private static final int WRITE_BATCH_SIZE = 100;
    private final DocumentStructureRepository structures;
    private final RetrievalUnitRepository units;
    private final RetrievalUnitPolicy policy;
    private final RecursiveCharSplitter splitter = new RecursiveCharSplitter();

    @Autowired
    public RetrievalUnitBuildStage(DocumentStructureRepository structures, RetrievalUnitRepository units,
            @Value("${modelrag.index.v2.max-unit-chars:4000}") int maxUnitChars,
            @Value("${modelrag.index.v2.overlap-chars:200}") int overlapChars,
            @Value("${modelrag.index.v2.max-units-per-node:128}") int maxUnitsPerNode,
            @Value("${modelrag.index.v2.max-units-per-build:100000}") int maxUnitsPerBuild) {
        this(structures, units, new RetrievalUnitPolicy(maxUnitChars, overlapChars, maxUnitsPerNode, maxUnitsPerBuild));
    }

    public RetrievalUnitBuildStage(DocumentStructureRepository structures, RetrievalUnitRepository units,
            RetrievalUnitPolicy policy) {
        this.structures = structures;
        this.units = units;
        this.policy = policy;
    }

    public long build(IndexBuildContext context, String documentName) {
        Map<Long, DocumentNode> nodes = readNodes(context.documentVersionId());
        Map<Long, String> paths = new LinkedHashMap<>();
        List<RetrievalUnitDraft> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        long generated = 0;
        for (DocumentNode node : nodes.values().stream()
                .sorted(Comparator.comparingInt(DocumentNode::depth).thenComparingInt(DocumentNode::ordinal))
                .toList()) {
            RetrievalUnitType type = typeOf(node);
            if (type == null || !node.searchable() || node.content() == null || node.content().isBlank()) continue;
            String titlePath = titlePath(node, nodes, paths, documentName);
            List<String> pieces = split(node.content());
            if (pieces.size() > policy.maxUnitsPerNode()) {
                throw new IllegalArgumentException("单个文档节点生成的检索单元超过上限");
            }
            for (int ordinal = 0; ordinal < pieces.size(); ordinal++) {
                if (++generated > policy.maxUnitsPerBuild()) {
                    throw new IllegalArgumentException("IndexBuild 检索单元超过上限");
                }
                String content = normalize(pieces.get(ordinal));
                String normalizedPath = normalize(titlePath);
                Map<String, Object> metadata = new LinkedHashMap<>(node.metadata());
                metadata.put("nodeType", node.nodeType().name());
                batch.add(new RetrievalUnitDraft(context.datasetId(), context.documentId(), context.documentVersionId(),
                        node.id(), context.buildId(), type, ordinal, normalizedPath, content,
                        hash(normalizedPath + "\n" + content), tokenCount(content), metadata));
                if (batch.size() >= WRITE_BATCH_SIZE) {
                    units.createBatch(List.copyOf(batch));
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) units.createBatch(List.copyOf(batch));
        return units.countByBuild(context.buildId());
    }

    private Map<Long, DocumentNode> readNodes(long versionId) {
        Map<Long, DocumentNode> nodes = new LinkedHashMap<>();
        for (int offset = 0;; offset += PAGE_SIZE) {
            List<DocumentNode> page = structures.findByVersion(versionId, offset, PAGE_SIZE);
            if (page.isEmpty()) break;
            page.forEach(node -> nodes.put(node.id(), node));
            if (page.size() < PAGE_SIZE) break;
        }
        return nodes;
    }

    private RetrievalUnitType typeOf(DocumentNode node) {
        return switch (node.nodeType()) {
            case PARAGRAPH, LIST_ITEM, CODE, QUOTE, CAPTION -> RetrievalUnitType.PARAGRAPH;
            case TABLE -> RetrievalUnitType.TABLE;
            case SECTION -> node.content() == null || node.content().isBlank() ? null : RetrievalUnitType.SECTION;
            default -> null;
        };
    }

    private String titlePath(DocumentNode node, Map<Long, DocumentNode> nodes, Map<Long, String> cache,
            String documentName) {
        String cached = cache.get(node.id());
        if (cached != null) return cached;
        List<String> titles = new ArrayList<>();
        DocumentNode current = node;
        int guard = 0;
        while (current != null && guard++ < 64) {
            if (current.title() != null && !current.title().isBlank()) titles.add(current.title().trim());
            current = current.parentId() == null ? null : nodes.get(current.parentId());
        }
        java.util.Collections.reverse(titles);
        if (titles.isEmpty() && documentName != null && !documentName.isBlank()) titles.add(documentName.trim());
        String path = String.join(" > ", titles);
        cache.put(node.id(), path);
        return path;
    }

    private List<String> split(String content) {
        if (content.length() <= policy.maxUnitChars()) return List.of(content);
        List<String> initial = splitter.split(content, policy.maxUnitChars(), policy.overlapChars());
        List<String> result = new ArrayList<>();
        for (String value : initial) {
            if (value.length() <= policy.maxUnitChars()) {
                result.add(value);
                continue;
            }
            int step = Math.max(1, policy.maxUnitChars() - policy.overlapChars());
            for (int start = 0; start < value.length();) {
                int end = Math.min(value.length(), start + policy.maxUnitChars());
                result.add(value.substring(start, end));
                if (end == value.length()) break;
                start += step;
            }
        }
        return List.copyOf(result);
    }

    private String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }

    private int tokenCount(String value) {
        double tokens = 0;
        for (int index = 0; index < value.length(); index++) {
            tokens += Character.UnicodeScript.of(value.charAt(index)) == Character.UnicodeScript.HAN ? 1 : .25;
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("检索单元内容哈希失败", error); }
    }
}
