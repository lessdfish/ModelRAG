package com.modelrag.knowledge.parser;

import com.modelrag.knowledge.model.NodeType;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Apache POI based DOCX parser preserving headings, lists, tables and rows. */
public final class DocxStructuredParser implements StructuredDocumentParser {
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    @Override
    public ParsedDocument parse(Path source, ParseLimits limits) throws Exception {
        if (source == null || limits == null) throw new IllegalArgumentException("解析源和限制不能为空");
        String fileName = source.getFileName() == null ? "document" : source.getFileName().toString();
        List<ParsedNode> nodes = new ArrayList<>();
        nodes.add(node("root", null, NodeType.DOCUMENT, 0, 0, fileName, "", false,
                ParserSupport.metadata("format", "docx")));
        ParseState state = new ParseState(nodes, limits.maxExtractedChars());
        try (InputStream input = Files.newInputStream(source); XWPFDocument document = new XWPFDocument(input)) {
            appendBody(document, document.getBodyElements(), state, null, true);
            appendHeadersAndFooters(document, state);
        }
        return new ParsedDocument(new DocumentParseMetadata(name(), version(), "STRUCTURED", true,
                ParserSupport.metadata("format", "docx")), nodes, List.of());
    }

    private void appendHeadersAndFooters(XWPFDocument document, ParseState state) {
        document.getHeaderList().forEach(header -> appendBody(header, header.getBodyElements(), state, "HEADER", false));
        document.getFooterList().forEach(footer -> appendBody(footer, footer.getBodyElements(), state, "FOOTER", false));
    }

    private void appendBody(IBody body, Iterable<IBodyElement> elements, ParseState state, String region,
            boolean searchable) {
        String parent = "root";
        int depth = 1;
        Map<String, Integer> ordinals = state.ordinals;
        if (region != null) {
            int ordinal = nextOrdinal(ordinals, "root");
            parent = childId("root", region.toLowerCase(Locale.ROOT), ordinal);
            state.nodes.add(node(parent, "root", NodeType.SECTION, 1, ordinal, region, "", false,
                    ParserSupport.metadata("region", region)));
            depth = 2;
        }
        Deque<Section> sections = new ArrayDeque<>();
        String currentParent = parent;
        int currentDepth = depth;
        String listParent = null;
        int listDepth = 0;
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                String value = paragraph.getText() == null ? "" : paragraph.getText().trim();
                if (value.isBlank()) {
                    listParent = null;
                    continue;
                }
                int headingLevel = headingLevel(paragraph);
                if (headingLevel > 0 && searchable) {
                    while (!sections.isEmpty() && sections.peek().level >= headingLevel) sections.pop();
                    currentParent = sections.isEmpty() ? parent : sections.peek().localId;
                    currentDepth = sections.isEmpty() ? depth : sections.peek().depth + 1;
                    int ordinal = nextOrdinal(ordinals, currentParent);
                    String id = childId(currentParent, "section", ordinal);
                    state.nodes.add(node(id, currentParent, NodeType.SECTION, currentDepth, ordinal, value, "", false,
                            ParserSupport.metadata("headingLevel", headingLevel, "format", "docx")));
                    sections.push(new Section(id, headingLevel, currentDepth));
                    listParent = null;
                } else if (paragraph.getNumID() != null && searchable) {
                    if (listParent == null) {
                        int ordinal = nextOrdinal(ordinals, currentParent);
                        listParent = childId(currentParent, "list", ordinal);
                        listDepth = currentDepth;
                        state.nodes.add(node(listParent, currentParent, NodeType.LIST, listDepth, ordinal, "", "", false,
                                ParserSupport.metadata("format", "docx")));
                    }
                    int itemOrdinal = nextOrdinal(ordinals, listParent);
                    state.nodes.add(node(childId(listParent, "item", itemOrdinal), listParent, NodeType.LIST_ITEM,
                            listDepth + 1, itemOrdinal, "", value, true, ParserSupport.metadata("format", "docx")));
                } else {
                    int ordinal = nextOrdinal(ordinals, currentParent);
                    state.nodes.add(node(childId(currentParent, "paragraph", ordinal), currentParent, NodeType.PARAGRAPH,
                            currentDepth, ordinal, "", value, searchable, ParserSupport.metadata("format", "docx")));
                    listParent = null;
                }
            } else if (element instanceof XWPFTable table) {
                int ordinal = nextOrdinal(ordinals, currentParent);
                String tableId = childId(currentParent, "table", ordinal);
                List<String> rows = new ArrayList<>();
                for (var row : table.getRows()) {
                    rows.add(row.getTableCells().stream().map(cell -> cell.getText().trim())
                            .collect(java.util.stream.Collectors.joining(" | ")));
                }
                state.append(String.join("\n", rows));
                state.nodes.add(node(tableId, currentParent, NodeType.TABLE, currentDepth, ordinal, "",
                        String.join("\n", rows), searchable, ParserSupport.metadata("format", "docx")));
                int rowOrdinal = 0;
                for (String row : rows) {
                    state.nodes.add(node(childId(tableId, "row", rowOrdinal), tableId, NodeType.TABLE_ROW,
                            currentDepth + 1, rowOrdinal, "", row, searchable,
                            ParserSupport.metadata("format", "docx")));
                    rowOrdinal++;
                }
                listParent = null;
            }
        }
    }

    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) style = paragraph.getStyleID();
        if (style == null || !style.toLowerCase(Locale.ROOT).startsWith("heading")) return 0;
        String digits = style.replaceAll("\\D", "");
        if (digits.isBlank()) return 1;
        try { return Math.max(1, Math.min(6, Integer.parseInt(digits))); }
        catch (NumberFormatException ignored) { return 1; }
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

    private record Section(String localId, int level, int depth) { }

    private final class ParseState {
        private final List<ParsedNode> nodes;
        private final Map<String, Integer> ordinals = new LinkedHashMap<>();
        private final int limit;
        private int extracted;

        private ParseState(List<ParsedNode> nodes, int limit) { this.nodes = nodes; this.limit = limit; }

        private void append(String value) {
            if (value == null) return;
            extracted += value.length();
            if (extracted > limit) throw new IllegalArgumentException("文档解析文本超过安全上限");
        }
    }

    @Override public String name() { return "docx-structured"; }
    @Override public String version() { return "1"; }
}
