package com.modelrag.knowledge.parser;

import com.modelrag.knowledge.model.NodeType;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative Markdown structure parser with deterministic parser-local IDs. */
public final class MarkdownStructuredParser implements StructuredDocumentParser {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])\\s+(.+)$");

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    @Override
    public ParsedDocument parse(Path source, ParseLimits limits) throws Exception {
        if (source == null || limits == null) throw new IllegalArgumentException("解析源和限制不能为空");
        String text = read(source, limits.maxExtractedChars());
        String fileName = source.getFileName() == null ? "document" : source.getFileName().toString();
        List<ParsedNode> nodes = new ArrayList<>();
        nodes.add(node("root", null, NodeType.DOCUMENT, 0, 0, fileName, "", false,
                ParserSupport.metadata("format", "markdown")));
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        Deque<Section> sections = new ArrayDeque<>();
        String[] lines = ParserSupport.normalize(text).split("\\n", -1);
        for (int index = 0; index < lines.length;) {
            String line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }
            Matcher heading = HEADING.matcher(line.trim());
            if (heading.matches()) {
                int level = heading.group(1).length();
                while (!sections.isEmpty() && sections.peek().level >= level) sections.pop();
                String parent = sections.isEmpty() ? "root" : sections.peek().localId;
                int ordinal = nextOrdinal(ordinals, parent);
                String localId = childId(parent, "section", ordinal);
                int depth = sections.isEmpty() ? 1 : sections.peek().depth + 1;
                nodes.add(node(localId, parent, NodeType.SECTION, depth, ordinal, heading.group(2), "", false,
                        ParserSupport.metadata("headingLevel", level)));
                sections.push(new Section(localId, level, depth));
                index++;
                continue;
            }
            String parent = sections.isEmpty() ? "root" : sections.peek().localId;
            int depth = sections.isEmpty() ? 1 : sections.peek().depth + 1;
            if (isFence(line)) {
                int start = index++;
                String language = line.trim().length() > 3 ? line.trim().substring(3).trim() : "";
                StringBuilder content = new StringBuilder();
                while (index < lines.length && !isFence(lines[index])) {
                    ParserSupport.appendBounded(content, lines[index], limits.maxExtractedChars());
                    content.append('\n');
                    index++;
                }
                if (index < lines.length) index++;
                addBlock(nodes, ordinals, parent, depth, NodeType.CODE, "", content.toString().stripTrailing(),
                        true, ParserSupport.metadata("language", language, "format", "markdown"));
                continue;
            }
            if (isTableStart(lines, index)) {
                List<String> rows = new ArrayList<>();
                while (index < lines.length && lines[index].trim().startsWith("|")) rows.add(lines[index++].trim());
                String tableContent = String.join("\n", rows);
                int tableOrdinal = nextOrdinal(ordinals, parent);
                String tableId = childId(parent, "table", tableOrdinal);
                nodes.add(node(tableId, parent, NodeType.TABLE, depth, tableOrdinal, "", tableContent, true,
                        ParserSupport.metadata("format", "markdown")));
                int rowOrdinal = 0;
                for (String row : rows) {
                    if (row.matches("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$")) continue;
                    String rowId = childId(tableId, "row", rowOrdinal);
                    String value = row.replaceFirst("^\\|", "").replaceFirst("\\|$", "").trim();
                    nodes.add(node(rowId, tableId, NodeType.TABLE_ROW,  depth + 1, rowOrdinal++, "", value, true,
                            ParserSupport.metadata("format", "markdown")));
                }
                continue;
            }
            if (line.trim().startsWith(">")) {
                StringBuilder content = new StringBuilder();
                while (index < lines.length && lines[index].trim().startsWith(">")) {
                    if (content.length() > 0) content.append('\n');
                    String value = lines[index++].trim().replaceFirst("^>\\s?", "");
                    ParserSupport.appendBounded(content, value, limits.maxExtractedChars());
                }
                addBlock(nodes, ordinals, parent, depth, NodeType.QUOTE, "", content.toString(), true,
                        ParserSupport.metadata("format", "markdown"));
                continue;
            }
            if (LIST.matcher(line).matches()) {
                int listOrdinal = nextOrdinal(ordinals, parent);
                String listId = childId(parent, "list", listOrdinal);
                nodes.add(node(listId, parent, NodeType.LIST, depth, listOrdinal, "", "", false,
                        ParserSupport.metadata("format", "markdown")));
                int itemOrdinal = 0;
                while (index < lines.length) {
                    Matcher item = LIST.matcher(lines[index]);
                    if (!item.matches()) break;
                    nodes.add(node(childId(listId, "item", itemOrdinal), listId, NodeType.LIST_ITEM,
                            depth + 1, itemOrdinal++, "", item.group(1).trim(), true,
                            ParserSupport.metadata("format", "markdown")));
                    index++;
                }
                continue;
            }
            StringBuilder paragraph = new StringBuilder();
            while (index < lines.length && !lines[index].isBlank() && !isBlockStart(lines, index)) {
                if (paragraph.length() > 0) paragraph.append('\n');
                ParserSupport.appendBounded(paragraph, lines[index].trim(), limits.maxExtractedChars());
                index++;
            }
            if (paragraph.length() > 0) {
                addBlock(nodes, ordinals, parent, depth, NodeType.PARAGRAPH, "", paragraph.toString(), true,
                        ParserSupport.metadata("format", "markdown"));
            } else if (index == startIndex(lines, line, index)) {
                index++;
            }
        }
        return new ParsedDocument(new DocumentParseMetadata(name(), version(), "DETERMINISTIC", true,
                ParserSupport.metadata("format", "markdown")), nodes, List.of());
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

    private boolean isBlockStart(String[] lines, int index) {
        String value = lines[index].trim();
        return HEADING.matcher(value).matches() || isFence(value) || value.startsWith(">")
                || LIST.matcher(value).matches() || isTableStart(lines, index);
    }

    private boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length || !lines[index].trim().startsWith("|")) return false;
        return lines[index + 1].trim().matches("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$");
    }

    private boolean isFence(String line) {
        String value = line == null ? "" : line.trim();
        return value.startsWith("```") || value.startsWith("~~~");
    }

    private void addBlock(List<ParsedNode> nodes, Map<String, Integer> ordinals, String parent, int depth,
            NodeType type, String title, String content, boolean searchable, Map<String, Object> metadata) {
        int ordinal = nextOrdinal(ordinals, parent);
        nodes.add(node(childId(parent, type.name().toLowerCase(Locale.ROOT), ordinal), parent, type, depth,
                ordinal, title, content, searchable, metadata));
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

    private int startIndex(String[] lines, String line, int current) { return Math.max(0, current - 1); }

    private record Section(String localId, int level, int depth) { }

    @Override public String name() { return "markdown-structured"; }
    @Override public String version() { return "1"; }
}
