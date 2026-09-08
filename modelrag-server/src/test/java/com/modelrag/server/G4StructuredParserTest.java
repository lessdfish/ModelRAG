package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.modelrag.indexing.pipeline.stage.DocumentParseStage;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.parser.DocxStructuredParser;
import com.modelrag.knowledge.parser.DocumentParseMetadata;
import com.modelrag.knowledge.parser.MarkdownStructuredParser;
import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.ParsedDocument;
import com.modelrag.knowledge.parser.ParsedNode;
import com.modelrag.knowledge.parser.PdfStructuredParser;
import com.modelrag.knowledge.parser.PlainTextStructuredParser;
import com.modelrag.knowledge.parser.StructuredDocumentParser;
import com.modelrag.knowledge.parser.StructuredDocumentParserRegistry;
import com.modelrag.knowledge.service.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class G4StructuredParserTest {
    private static final ParseLimits LIMITS = new ParseLimits(100, 100_000);

    @TempDir
    Path temporary;

    @Test
    void markdownAndTextRootsUseTheLogicalDocumentName() throws Exception {
        Path markdown = Files.writeString(temporary.resolve("random-upload-temp.bin"), "# Policy\n\nbody", StandardCharsets.UTF_8);
        Path text = Files.writeString(temporary.resolve("another-random-upload.bin"), "1.1 Overview\n\nbody", StandardCharsets.UTF_8);

        ParsedDocument markdownResult = new MarkdownStructuredParser().parse(markdown, "policy.md", LIMITS);
        ParsedDocument textResult = new PlainTextStructuredParser().parse(text, "runbook.txt", LIMITS);

        assertEquals("policy.md", markdownResult.nodes().get(0).title());
        assertEquals("runbook.txt", textResult.nodes().get(0).title());
        assertTrue(markdownResult.nodes().stream().anyMatch(node -> node.nodeType() == NodeType.SECTION));
        assertTrue(textResult.nodes().stream().anyMatch(node -> node.nodeType() == NodeType.SECTION));
    }

    @Test
    void docxAndPdfRootsUseTheLogicalDocumentName() throws Exception {
        Path docx = temporary.resolve("modelrag-structured-random.docx");
        try (var output = Files.newOutputStream(docx); ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            put(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + "<w:p><w:r><w:t>Document body</w:t></w:r></w:p>"
                    + "</w:body></w:document>");
            zip.finish();
        }
        Path pdf = temporary.resolve("modelrag-structured-random.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                stream.newLineAtOffset(60, 740);
                stream.showText("Document body");
                stream.endText();
            }
            document.save(pdf.toFile());
        }

        assertEquals("manual.docx", new DocxStructuredParser().parse(docx, "manual.docx", LIMITS)
                .nodes().get(0).title());
        assertEquals("policy.pdf", new PdfStructuredParser().parse(pdf, "policy.pdf", LIMITS)
                .nodes().get(0).title());
    }

    private void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void parseStagePassesDocumentFileNameInsteadOfItsRandomTempPath() throws Exception {
        AtomicReference<String> logicalName = new AtomicReference<>();
        StructuredDocumentParser parser = new StructuredDocumentParser() {
            @Override public boolean supports(String fileName) { return "policy.md".equals(fileName); }
            @Override public ParsedDocument parse(Path source, String fileName, ParseLimits limits) {
                logicalName.set(fileName);
                return new ParsedDocument(new DocumentParseMetadata("test", "1", Map.of()),
                        List.of(new ParsedNode("root", null, NodeType.DOCUMENT, 0, 0, fileName,
                                "", false, Map.of())), List.of());
            }
            @Override public String name() { return "test"; }
            @Override public String version() { return "1"; }
        };
        DocumentParseStage stage = new DocumentParseStage(new StructuredDocumentParserRegistry(List.of(parser)),
                mock(ObjectStorageService.class), LIMITS, 10_000, 5);
        Document document = new Document(9, 7, "policy.md", "MD", "hash", "body", "READY", null, 0);
        DocumentVersion version = new DocumentVersion(19, 9, 1, "hash", null, null, "hash", "test", "1",
                DocumentParseStatus.READY, Map.of(), Instant.now(), Instant.now());

        assertEquals("policy.md", stage.parse(document, version).nodes().get(0).title());
        assertEquals("policy.md", logicalName.get());
    }
}
