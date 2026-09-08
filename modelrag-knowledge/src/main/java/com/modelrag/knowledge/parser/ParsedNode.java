package com.modelrag.knowledge.parser;

import com.modelrag.knowledge.model.NodeType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parser-local structural node. It intentionally contains no database identifiers. */
public record ParsedNode(String localId, String parentLocalId, NodeType nodeType, int depth, int ordinal,
        String title, String content, Integer pageFrom, Integer pageTo, Long charStart, Long charEnd,
        int tokenCount, boolean searchable, Map<String, Object> metadata) {
    public ParsedNode {
        if (localId == null || localId.isBlank() || nodeType == null) {
            throw new IllegalArgumentException("解析节点 ID 和类型不能为空");
        }
        if (parentLocalId != null && parentLocalId.isBlank()) parentLocalId = null;
        if (localId.equals(parentLocalId) || depth < 0 || ordinal < 0 || tokenCount < 0) {
            throw new IllegalArgumentException("解析节点层级或序号无效");
        }
        if ((pageFrom == null) != (pageTo == null)
                || (pageFrom != null && (pageFrom < 1 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("解析节点页码范围无效");
        }
        if ((charStart == null) != (charEnd == null)
                || (charStart != null && (charStart < 0 || charEnd < charStart))) {
            throw new IllegalArgumentException("解析节点字符范围无效");
        }
        title = title == null ? "" : title.trim();
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public ParsedNode(String localId, String parentLocalId, NodeType nodeType, int depth, int ordinal,
            String title, String content, boolean searchable, Map<String, Object> metadata) {
        this(localId, parentLocalId, nodeType, depth, ordinal, title, content,
                null, null, null, null, ParserSupport.tokenCount(content), searchable, metadata);
    }
}
