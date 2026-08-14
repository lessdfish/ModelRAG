package com.modelrag.knowledge.parser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

public final class TextDocumentParsers {
    private TextDocumentParsers() { }
    public static java.util.List<DocumentParser> defaults() {
        return java.util.List.of(new Pdf(), new Docx(), new Plain("markdown", ".md"), new Plain("text", ".txt"));
    }
    public static DocumentParser forFile(String fileName) {
        return defaults().stream().filter(parser -> parser.supports(fileName.toLowerCase())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("只支持 PDF、DOCX、MD、TXT 文件"));
    }
    private static final class Plain implements DocumentParser {
        private final String name;
        private final String suffix;

        Plain(String name, String suffix) { this.name = name; this.suffix = suffix; }

        public boolean supports(String f) { return f.endsWith(suffix); }

        public String parse(Path source, ParseLimits limits) throws Exception {
            StringWriter result = new StringWriter();
            parseTo(source, limits, result);
            return result.toString();
        }

        public void parseTo(Path source, ParseLimits limits, Writer target) throws Exception {
            BoundedWriter output = new BoundedWriter(target, limits.maxExtractedChars());
            try (BufferedReader reader = Files.newBufferedReader(source, charset(source))) {
                char[] buffer = new char[8192];
                int read;
                boolean first = true;
                while ((read = reader.read(buffer)) >= 0) {
                    int offset = first && read > 0 && buffer[0] == '\uFEFF' ? 1 : 0;
                    first = false;
                    output.write(buffer, offset, read - offset);
                }
            }
        }

        private Charset charset(Path source) throws IOException {
            try (InputStream input = Files.newInputStream(source)) {
                byte[] prefix = input.readNBytes(3);
                if (prefix.length >= 2 && prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe) {
                    return StandardCharsets.UTF_16LE;
                }
                if (prefix.length >= 2 && prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff) {
                    return StandardCharsets.UTF_16BE;
                }
                return StandardCharsets.UTF_8;
            }
        }

        public String name() { return name; }
    }

    private static final class Pdf implements DocumentParser {
        public boolean supports(String f) { return f.endsWith(".pdf"); }

        public String parse(Path source, ParseLimits limits) throws Exception {
            StringWriter result = new StringWriter();
            parseTo(source, limits, result);
            return result.toString();
        }

        public void parseTo(Path source, ParseLimits limits, Writer target) throws Exception {
            BoundedWriter output = new BoundedWriter(target, limits.maxExtractedChars());
            try (var pdf = Loader.loadPDF(source.toFile())) {
                if (pdf.getNumberOfPages() > limits.maxPages()) {
                    throw new IllegalArgumentException("PDF 页数超过安全上限");
                }
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                java.util.Set<String> repeatedEdges = repeatedEdgeLines(pdf.getNumberOfPages(), stripper, pdf);
                boolean extractedText = false;
                for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    java.util.List<String> lines = cleanLines(stripper.getText(pdf), repeatedEdges);
                    output.write("\n[[MODELRAG_PAGE:" + page + "]]\n");
                    if (!lines.isEmpty()) {
                        extractedText = true;
                        String first = lines.get(0);
                        if (titleCandidate(first)) output.write("# " + first + "\n");
                        for (int index = titleCandidate(first) ? 1 : 0; index < lines.size(); index++) {
                            output.write(lines.get(index));
                            output.write('\n');
                        }
                    }
                }
                if (!extractedText) {
                    throw new BusinessException(ErrorCode.OCR_REQUIRED, "扫描型 PDF 未包含可提取文本，需要先完成 OCR");
                }
            }
        }

        private java.util.Set<String> repeatedEdgeLines(int pageCount, PDFTextStripper stripper,
                org.apache.pdfbox.pdmodel.PDDocument pdf) throws IOException {
            if (pageCount < 2) return java.util.Set.of();
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                java.util.List<String> lines = normalizedLines(stripper.getText(pdf));
                java.util.LinkedHashSet<String> edges = new java.util.LinkedHashSet<>();
                for (int i = 0; i < Math.min(2, lines.size()); i++) edges.add(lines.get(i));
                for (int i = Math.max(0, lines.size() - 2); i < lines.size(); i++) edges.add(lines.get(i));
                edges.stream().filter(line -> line.length() <= 160)
                        .forEach(line -> counts.merge(line, 1, Integer::sum));
            }
            int threshold = Math.max(2, (int) Math.ceil(pageCount * .5));
            return counts.entrySet().stream().filter(entry -> entry.getValue() >= threshold)
                    .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        private java.util.List<String> cleanLines(String text, java.util.Set<String> repeatedEdges) {
            java.util.List<String> lines = normalizedLines(text);
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                boolean edge = index < 2 || index >= lines.size() - 2;
                if (!(edge && repeatedEdges.contains(lines.get(index)))) result.add(lines.get(index));
            }
            return result;
        }

        private java.util.List<String> normalizedLines(String text) {
            return java.util.Arrays.stream((text == null ? "" : text).replace("\r\n", "\n").split("\n"))
                    .map(line -> line.replaceAll("\\s+", " ").trim()).filter(line -> !line.isBlank()).toList();
        }

        private boolean titleCandidate(String line) {
            return line != null && line.length() >= 2 && line.length() <= 80
                    && !line.matches(".*[。！？.!?；;:]$") && !line.matches("^[0-9]+$");
        }

        public String name() { return "pdfbox"; }
    }

    private static final class Docx implements DocumentParser {
        public boolean supports(String f) { return f.endsWith(".docx"); }

        public String parse(Path source, ParseLimits limits) throws Exception {
            StringWriter result = new StringWriter();
            parseTo(source, limits, result);
            return result.toString();
        }

        public void parseTo(Path source, ParseLimits limits, Writer target) throws Exception {
            BoundedWriter output = new BoundedWriter(target, limits.maxExtractedChars());
            try (InputStream input = Files.newInputStream(source); var doc = new XWPFDocument(input)) {
                for (var header : doc.getHeaderList()) appendBody(header.getBodyElements(), output, "HEADER");
                appendBody(doc.getBodyElements(), output, null);
                for (var footer : doc.getFooterList()) appendBody(footer.getBodyElements(), output, "FOOTER");
            }
        }

        private void appendBody(Iterable<IBodyElement> elements, Writer output, String region) throws IOException {
            if (region != null) appendLine(output, "[[MODELRAG_" + region + "]]", null);
            for (IBodyElement element : elements) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendLine(output, paragraph.getText(), paragraph);
                } else if (element instanceof XWPFTable table) {
                    appendLine(output, "[[MODELRAG_TABLE]]", null);
                    for (var row : table.getRows()) {
                        appendLine(output, "| " + row.getTableCells().stream().map(cell -> cell.getText().trim())
                                .collect(java.util.stream.Collectors.joining(" | ")) + " |", null);
                    }
                    appendLine(output, "[[/MODELRAG_TABLE]]", null);
                }
            }
        }

        private void appendLine(Writer output, String value, XWPFParagraph paragraph) throws IOException {
            if (value == null || value.isBlank()) return;
            String prefix = "";
            if (paragraph != null && paragraph.getStyle() != null
                    && paragraph.getStyle().toLowerCase(java.util.Locale.ROOT).startsWith("heading")) {
                String digits = paragraph.getStyle().replaceAll("\\D", "");
                int level = digits.isBlank() ? 1 : Math.max(1, Math.min(6, Integer.parseInt(digits)));
                prefix = "#".repeat(level) + " ";
            } else if (paragraph != null && paragraph.getNumID() != null) {
                prefix = "- ";
            }
            output.write(prefix + value.trim());
            output.write('\n');
        }

        public String name() { return "poi"; }
    }

    private static final class BoundedWriter extends Writer {
        private final Writer delegate;
        private final int limit;
        private int count;
        private boolean nonWhitespace;

        private BoundedWriter(Writer delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(char[] value, int offset, int length) throws IOException {
            if (count + length > limit) throw new IllegalArgumentException("文档解析文本超过安全上限");
            for (int index = offset; index < offset + length; index++) {
                if (!Character.isWhitespace(value[index])) nonWhitespace = true;
            }
            delegate.write(value, offset, length);
            count += length;
        }

        @Override
        public void write(int value) throws IOException {
            char[] one = {(char) value};
            write(one, 0, 1);
        }

        @Override
        public void write(String value, int offset, int length) throws IOException {
            char[] chars = value.substring(offset, offset + length).toCharArray();
            write(chars, 0, chars.length);
        }

        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() { }
        private boolean hasNonWhitespace() { return nonWhitespace; }
    }
}
