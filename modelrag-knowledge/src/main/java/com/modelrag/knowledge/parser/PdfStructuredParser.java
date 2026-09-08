package com.modelrag.knowledge.parser;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.NodeType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** PDFBox heuristic parser retaining page locators and rejecting textless PDFs for OCR. */
public final class PdfStructuredParser implements StructuredDocumentParser {
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    @Override
    public ParsedDocument parse(Path source, String logicalFileName, ParseLimits limits) throws Exception {
        if (source == null || limits == null) throw new IllegalArgumentException("解析源和限制不能为空");
        String fileName = ParserSupport.requireLogicalFileName(logicalFileName);
        List<ParsedNode> nodes = new ArrayList<>();
        nodes.add(node("root", null, NodeType.DOCUMENT, 0, 0, fileName, "", false, null, null,
                ParserSupport.metadata("format", "pdf", "parserQuality", "HEURISTIC", "layoutPreserved", false)));
        boolean extracted = false;
        int totalChars = 0;
        try (var pdf = Loader.loadPDF(source.toFile())) {
            if (pdf.getNumberOfPages() > limits.maxPages()) throw new IllegalArgumentException("PDF 页数超过安全上限");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            Set<String> repeatedEdges = repeatedEdgeLines(pdf.getNumberOfPages(), stripper, pdf);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                List<String> lines = cleanLines(stripper.getText(pdf), repeatedEdges);
                if (!lines.isEmpty()) extracted = true;
                int pageOrdinal = page - 1;
                String pageId = "root/page-" + page;
                nodes.add(node(pageId, "root", NodeType.SECTION, 1, pageOrdinal, "Page " + page, "", false,
                        page, page, ParserSupport.metadata("page", page, "format", "pdf")));
                int ordinal = 0;
                for (String line : lines) {
                    totalChars += line.length();
                    if (totalChars > limits.maxExtractedChars()) throw new IllegalArgumentException("文档解析文本超过安全上限");
                    nodes.add(node(pageId + "/paragraph-" + (ordinal + 1), pageId, NodeType.PARAGRAPH, 2,
                            ordinal++, "", line, true, page, page, ParserSupport.metadata("format", "pdf")));
                }
            }
        }
        if (!extracted) throw new BusinessException(ErrorCode.OCR_REQUIRED, "扫描型 PDF 未包含可提取文本，需要先完成 OCR");
        return new ParsedDocument(new DocumentParseMetadata(name(), version(), "HEURISTIC", false,
                ParserSupport.metadata("format", "pdf", "layoutPreserved", false)), nodes, List.of());
    }

    private Set<String> repeatedEdgeLines(int pageCount, PDFTextStripper stripper,
            org.apache.pdfbox.pdmodel.PDDocument pdf) throws IOException {
        if (pageCount < 2) return Set.of();
        Map<String, Integer> counts = new HashMap<>();
        for (int page = 1; page <= pageCount; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            List<String> lines = normalizedLines(stripper.getText(pdf));
            List<String> edges = new ArrayList<>();
            for (int i = 0; i < Math.min(2, lines.size()); i++) edges.add(lines.get(i));
            for (int i = Math.max(0, lines.size() - 2); i < lines.size(); i++) edges.add(lines.get(i));
            edges.stream().filter(value -> value.length() <= 160).forEach(value -> counts.merge(value, 1, Integer::sum));
        }
        int threshold = Math.max(2, (int) Math.ceil(pageCount * .5));
        return counts.entrySet().stream().filter(entry -> entry.getValue() >= threshold)
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    private List<String> cleanLines(String text, Set<String> repeatedEdges) {
        List<String> lines = normalizedLines(text);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            boolean edge = index < 2 || index >= lines.size() - 2;
            if (!(edge && repeatedEdges.contains(lines.get(index)))) result.add(lines.get(index));
        }
        return result;
    }

    private List<String> normalizedLines(String text) {
        return java.util.Arrays.stream((text == null ? "" : text).replace("\r\n", "\n").split("\n"))
                .map(value -> value.replaceAll("\\s+", " ").trim()).filter(value -> !value.isBlank()).toList();
    }

    private ParsedNode node(String id, String parent, NodeType type, int depth, int ordinal, String title,
            String content, boolean searchable, Integer pageFrom, Integer pageTo, Map<String, Object> metadata) {
        return new ParsedNode(id, parent, type, depth, ordinal, title, content, pageFrom, pageTo, null, null,
                ParserSupport.tokenCount(content), searchable, metadata);
    }

    @Override public String name() { return "pdf-structured"; }
    @Override public String version() { return "1"; }
}
