package com.modelrag.knowledge.parser;

import com.modelrag.knowledge.model.NodeType;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete parser-local document tree plus explicit non-tree relations. */
public record ParsedDocument(DocumentParseMetadata metadata, List<ParsedNode> nodes, List<ParsedEdge> edges) {
    public ParsedDocument {
        if (metadata == null) throw new IllegalArgumentException("解析元数据不能为空");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        validateIntegrity(nodes, edges);
    }

    public ParsedDocument(List<ParsedNode> nodes, List<ParsedEdge> edges, DocumentParseMetadata metadata) {
        this(metadata, nodes, edges);
    }

    public Map<String, ParsedNode> nodesByLocalId() {
        Map<String, ParsedNode> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.put(node.localId(), node));
        return Collections.unmodifiableMap(result);
    }

    private static void validateIntegrity(List<ParsedNode> nodes, List<ParsedEdge> edges) {
        if (nodes.isEmpty()) throw new IllegalArgumentException("解析结果不能为空");
        Map<String, ParsedNode> byId = new LinkedHashMap<>();
        Set<String> siblings = new HashSet<>();
        int roots = 0;
        for (ParsedNode node : nodes) {
            if (byId.put(node.localId(), node) != null) {
                throw new IllegalArgumentException("解析节点 localId 必须唯一: " + node.localId());
            }
            if (node.parentLocalId() == null) {
                roots++;
                if (node.depth() != 0) throw new IllegalArgumentException("根节点深度必须为 0");
            } else {
                ParsedNode parent = byId.get(node.parentLocalId());
                if (parent == null) throw new IllegalArgumentException("解析节点父级不存在: " + node.parentLocalId());
                if (node.depth() != parent.depth() + 1) throw new IllegalArgumentException("解析节点深度不连续");
                if (!siblings.add(node.parentLocalId() + "\u0000" + node.ordinal())) {
                    throw new IllegalArgumentException("解析节点兄弟序号重复");
                }
            }
        }
        if (roots != 1 || nodes.stream().filter(node -> node.parentLocalId() == null)
                .noneMatch(node -> node.nodeType() == NodeType.DOCUMENT)) {
            throw new IllegalArgumentException("解析结果必须包含一个 DOCUMENT 根节点");
        }
        for (ParsedEdge edge : edges) {
            if (!byId.containsKey(edge.fromLocalId()) || !byId.containsKey(edge.toLocalId())) {
                throw new IllegalArgumentException("解析边端点不存在");
            }
        }
    }
}
