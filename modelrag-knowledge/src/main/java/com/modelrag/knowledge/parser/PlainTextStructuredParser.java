package com.modelrag.knowledge.parser;

import com.modelrag.knowledge.model.NodeType;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Conservative TXT parser; ordinary short lines remain paragraph content. */
public final class PlainTextStructuredParser implements StructuredDocumentParser {
    private static final Pattern HIGH_CONFIDENCE_HEADING = Pattern.compile(
            "^(?:第[一二三四五六七八九十百]+[章节篇]|\\d+(?:\\.\\d+)+|[一二三四五六七八九十]+、)\\s*.{1,100}$");

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    @Override
    public ParsedDocument parse(Path source, String logicalFileName, ParseLimits limits) throws Exception {
        if (source == null || limits == null) throw new IllegalArgumentException("解析源和限制不能为空");
        String text = read(source, limits.maxExtractedChars());
        String fileName = ParserSupport.requireLogicalFileName(logicalFileName);
        List<ParsedNode> nodes = new ArrayList<>();
        nodes.add(node("root", null, NodeType.DOCUMENT, 0, 0, fileName, "", false,
                ParserSupport.metadata("format", "txt", "parserQuality", "CONSERVATIVE")));
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        String currentParent = "root";
        int currentDepth = 1;
        StringBuilder paragraph = new StringBuilder();
        String[] lines = ParserSupport.normalize(text).split("\\n", -1);
        for (String line : lines) {
            String value = line.trim();
            if (value.isBlank()) {
                flush(nodes, ordinals, currentParent, currentDepth, paragraph);
                continue;
            }
            if (HIGH_CONFIDENCE_HEADING.matcher(value).matches()) {
                flush(nodes, ordinals, currentParent, currentDepth, paragraph);
                int ordinal = nextOrdinal(ordinals, "root");
                String id = childId("root", "section", ordinal);
                nodes.add(node(id, "root", NodeType.SECTION, 1, ordinal, value, "", false,
                        ParserSupport.metadata("format", "txt", "headingConfidence", "HIGH")));
                currentParent = id;
                currentDepth = 2;
                continue;
            }
            if (paragraph.length() > 0) paragraph.append('\n');
            ParserSupport.appendBounded(paragraph, value, limits.maxExtractedChars());
        }
        flush(nodes, ordinals, currentParent, currentDepth, paragraph);
        return new ParsedDocument(new DocumentParseMetadata(name(), version(), "CONSERVATIVE", true,
                ParserSupport.metadata("format", "txt")), nodes, List.of());
    }

    private void flush(List<ParsedNode> nodes, Map<String, Integer> ordinals, String parent, int depth,
            StringBuilder paragraph) {
        if (paragraph.isEmpty()) return;
        int ordinal = nextOrdinal(ordinals, parent);
        nodes.add(node(childId(parent, "paragraph", ordinal), parent, NodeType.PARAGRAPH, depth, ordinal, "",
                paragraph.toString(), true, ParserSupport.metadata("format", "txt")));
        paragraph.setLength(0);
    }

    private String read(Path source, int limit) throws IOException {
        StringBuilder result = new StringBuilder();
        try (var reader = Files.newBufferedReader(source, charset(source))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (result.length() + read > limit) throw new IllegalArgumentException("文档解析文本超过安全上限");
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    private Charset charset(Path source) throws IOException {
        try (var input = Files.newInputStream(source)) {
            byte[] prefix = input.readNBytes(3);
            if (prefix.length >= 2 && prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe) return StandardCharsets.UTF_16LE;
            if (prefix.length >= 2 && prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff) return StandardCharsets.UTF_16BE;
            return StandardCharsets.UTF_8;
        }
    }

    private ParsedNode node(String id, String parent, NodeType type, int depth, int ordinal, String title,
            String content, boolean searchable, Map<String, Object> metadata) {
        return new ParsedNode(id, parent, type, depth, ordinal, title, content, null, null, null, null,
                ParserSupport.tokenCount(content), searchable, metadata);
    }

    private int nextOrdinal(Map<String, Integer> ordinals, String parent) {
        int value = ordinals.getOrDefault(parent, 0);
        ordinals.put(parent, value + 1);
        return value;
    }

    private String childId(String parent, String type, int ordinal) { return parent + "/" + type + "-" + (ordinal + 1); }

    @Override public String name() { return "text-structured"; }
    @Override public String version() { return "1"; }
}
