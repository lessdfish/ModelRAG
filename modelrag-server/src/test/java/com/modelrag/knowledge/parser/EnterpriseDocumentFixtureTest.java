package com.modelrag.knowledge.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.modelrag.knowledge.service.BasicDocumentSafetyScanner;
import com.modelrag.knowledge.service.DocumentService;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.knowledge.service.ObjectStorageService;
import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;

/** Regenerable parser-scale fixtures matching the refactor acceptance corpus. */
class EnterpriseDocumentFixtureTest {
    private static final ParseLimits LIMITS = new ParseLimits(1_000, 5_000_000);

    @Test
    void parsesRegenerableMarkdownAndTextScaleFixtures() throws Exception {
        Path markdown = Files.createTempFile("modelrag-enterprise-", ".md");
        Path text = Files.createTempFile("modelrag-enterprise-", ".txt");
        try {
            Files.writeString(markdown, markdownFixture(), StandardCharsets.UTF_8);
            Files.writeString(text, textFixture(), StandardCharsets.UTF_8);
            String markdownText = TextDocumentParsers.forFile("policy.md").parse(markdown, LIMITS);
            String plainText = TextDocumentParsers.forFile("runbook.txt").parse(text, LIMITS);
            assertTrue(markdownText.length() >= 20_000);
            assertTrue(plainText.length() >= 100_000);
            assertTrue(markdownText.contains("```java") && markdownText.contains("| Version |"));
            assertTrue(plainText.contains("超长行") && plainText.contains("步骤 120"));
        } finally {
            Files.deleteIfExists(markdown);
            Files.deleteIfExists(text);
        }
    }

    @Test
    void parsesThirtyPagePdfAndStructuredDocxFixtures() throws Exception {
        Path pdf = Files.createTempFile("modelrag-enterprise-", ".pdf");
        Path docx = Files.createTempFile("modelrag-enterprise-", ".docx");
        try {
            Files.write(pdf, pdfFixture(30));
            Files.write(docx, docxFixture(35));
            String pdfText = TextDocumentParsers.forFile("policy.pdf").parse(pdf, LIMITS);
            String docxText = TextDocumentParsers.forFile("manual.docx").parse(docx, LIMITS);
            assertTrue(pdfText.contains("POLICY-ID POLICY-") && pdfText.split("\\R").length >= 30);
            assertTrue(pdfText.contains("[[MODELRAG_PAGE:1]]") && pdfText.contains("[[MODELRAG_PAGE:30]]"));
            assertTrue(docxText.contains("手册章节 35") && docxText.contains("责任人"));
        } finally {
            Files.deleteIfExists(pdf);
            Files.deleteIfExists(docx);
        }
    }

    @Test
    void parsesOneHundredPagePdfFixtureWithinConfiguredPageBoundary() throws Exception {
        Path pdf = Files.createTempFile("modelrag-enterprise-100-page-", ".pdf");
        try {
            Files.write(pdf, pdfFixture(100));
            String text = TextDocumentParsers.forFile("policy.pdf").parse(pdf, LIMITS);
            assertTrue(text.contains("[[MODELRAG_PAGE:1]]"));
            assertTrue(text.contains("[[MODELRAG_PAGE:100]]"));
            assertTrue(text.contains("POLICY-ID POLICY-100 PAGE 100"));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void streamsTenMiBTextWithoutCreatingAWholeFileByteArray() throws Exception {
        long bytes = 10L * 1024 * 1024;
        Path text = Files.createTempFile("modelrag-enterprise-10mib-", ".txt");
        Path artifact = Files.createTempFile("modelrag-enterprise-10mib-artifact-", ".txt");
        try {
            byte[] block = "enterprise ingestion boundary fixture\n".repeat(2048)
                    .getBytes(StandardCharsets.UTF_8);
            try (var output = Files.newOutputStream(text)) {
                long written = 0;
                while (written < bytes) {
                    int length = (int) Math.min(block.length, bytes - written);
                    output.write(block, 0, length);
                    written += length;
                }
            }
            try (var writer = Files.newBufferedWriter(artifact, StandardCharsets.UTF_8)) {
                TextDocumentParsers.forFile("large.txt").parseTo(text,
                        new ParseLimits(1_000, 12 * 1024 * 1024), writer);
            }
            assertEquals(bytes, Files.size(text));
            assertEquals(bytes, Files.size(artifact));
        } finally {
            Files.deleteIfExists(text);
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void acceptsExactFiftyMiBBoundaryAndFailsOnExtractedTextLimitWithoutSavingMetadata() throws Exception {
        long bytes = 50L * 1024 * 1024;
        KnowledgeStore store = mock(KnowledgeStore.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        DocumentService service = new DocumentService(store, storage, mock(ApplicationEventPublisher.class),
                1_000, 5_000_000, bytes);
        byte[] block = "enterprise-boundary-fixture\n".repeat(2048).getBytes(StandardCharsets.UTF_8);
        var input = new java.io.InputStream() {
            private long remaining = bytes;
            private int offset;
            @Override public int read() {
                if (remaining == 0) return -1;
                int value = block[offset++] & 0xff;
                if (offset == block.length) offset = 0;
                remaining--;
                return value;
            }
            @Override public int read(byte[] target, int start, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(length, remaining);
                for (int index = 0; index < count; index++) {
                    target[start + index] = block[offset++];
                    if (offset == block.length) offset = 0;
                }
                remaining -= count;
                return count;
            }
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.upload(1, "boundary.txt", "text/plain", bytes, input));
        assertTrue(error.getMessage().contains("解析文本超过安全上限"));
        verify(storage).put(org.mockito.ArgumentMatchers.contains("/source.txt"), any(),
                org.mockito.ArgumentMatchers.eq(bytes), anyString());
        verify(store, never()).addDocument(anyLong(), anyString(), anyString(), anyString(), any(),
                any(), any(), anyString());
    }

    @Test
    void rejectsOversizedUploadBeforeReadingOrPersistingContent() throws Exception {
        KnowledgeStore store = mock(KnowledgeStore.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        DocumentService service = new DocumentService(store, storage, mock(ApplicationEventPublisher.class),
                1_000, 5_000_000, 50L * 1024 * 1024);
        java.util.concurrent.atomic.AtomicBoolean read = new java.util.concurrent.atomic.AtomicBoolean();
        var input = new java.io.InputStream() {
            @Override public int read() { read.set(true); return -1; }
        };

        assertThrows(IllegalArgumentException.class, () -> service.upload(1, "too-large.txt", "text/plain",
                50L * 1024 * 1024 + 1, input));
        assertTrue(!read.get());
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
        verify(store, never()).addDocument(anyLong(), anyString(), anyString(), anyString(), any(),
                any(), any(), anyString());
    }

    @Test
    void scannedPdfRequiresOcrAndRepeatedPageEdgesAreRemoved() throws Exception {
        Path scanned = Files.createTempFile("modelrag-scanned-", ".pdf");
        Path repeated = Files.createTempFile("modelrag-repeated-", ".pdf");
        try {
            Files.deleteIfExists(scanned);
            try (PDDocument document = new PDDocument()) {
                document.addPage(new PDPage());
                document.save(scanned.toFile());
            }
            BusinessException error = assertThrows(BusinessException.class,
                    () -> TextDocumentParsers.forFile("scan.pdf").parse(scanned, LIMITS));
            assertEquals(ErrorCode.OCR_REQUIRED, error.errorCode());

            Files.write(repeated, repeatedEdgePdfFixture());
            String text = TextDocumentParsers.forFile("repeated.pdf").parse(repeated, LIMITS);
            assertTrue(!text.contains("CONFIDENTIAL FOOTER"));
            assertTrue(text.contains("Unique clause 1") && text.contains("Unique clause 3"));
        } finally {
            Files.deleteIfExists(scanned);
            Files.deleteIfExists(repeated);
        }
    }

    @Test
    void rejectsZipBombTraversalAndExcessiveNestedArchives() throws Exception {
        BasicDocumentSafetyScanner scanner = new BasicDocumentSafetyScanner();
        Path bomb = Files.createTempFile("modelrag-zip-bomb-", ".docx");
        Path traversal = Files.createTempFile("modelrag-zip-traversal-", ".docx");
        Path nested = Files.createTempFile("modelrag-zip-nested-", ".docx");
        try {
            Files.write(bomb, docxWithExtra("word/bomb.bin", new byte[2_000_000]));
            Files.write(traversal, docxWithExtra("../escape.bin", "escape".getBytes(StandardCharsets.UTF_8)));
            Files.write(nested, docxWithExtra("word/embeddings/level1.zip", nestedZip(4)));

            assertThrows(IllegalArgumentException.class,
                    () -> scanner.scan(bomb, "bomb.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 5_000_000));
            assertThrows(IllegalArgumentException.class,
                    () -> scanner.scan(traversal, "traversal.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 5_000_000));
            assertThrows(IllegalArgumentException.class,
                    () -> scanner.scan(nested, "nested.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 5_000_000));
        } finally {
            Files.deleteIfExists(bomb);
            Files.deleteIfExists(traversal);
            Files.deleteIfExists(nested);
        }
    }

    private byte[] docxWithExtra(String entryName, byte[] value) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "[Content_Types].xml", "<Types/>");
            put(zip, "word/document.xml", "<document/>");
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(value);
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }

    private byte[] nestedZip(int depth) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(depth <= 1 ? "leaf.txt" : "level" + depth + ".zip"));
            byte[] value = depth <= 1 ? "leaf".getBytes(StandardCharsets.UTF_8) : nestedZip(depth - 1);
            zip.write(value);
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }

    private byte[] repeatedEdgePdfFixture() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 3; page++) {
                PDPage pdfPage = new PDPage();
                document.addPage(pdfPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdfPage)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    stream.newLineAtOffset(60, 740);
                    stream.showText("CONFIDENTIAL FOOTER");
                    stream.newLineAtOffset(0, -20);
                    stream.showText("Unique clause " + page);
                    stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private String markdownFixture() {
        StringBuilder text = new StringBuilder("# Enterprise Policy Manual\n\n");
        text.append("| Version | Status |\n|---|---|\n| v1 | active |\n\n");
        for (int i = 1; i <= 220; i++) {
            text.append("## Chapter ").append(i).append("\n");
            text.append("This chapter describes policy boundaries, approval conditions, recovery steps, and POLICY-").append(i).append(".\n");
            text.append("```java\npublic void step").append(i).append("() { /* controlled operation */ }\n```\n\n");
        }
        return text.toString();
    }

    private String textFixture() {
        StringBuilder text = new StringBuilder();
        String longLine = "超长行：" + "x".repeat(900);
        for (int i = 1; i <= 120; i++) {
            text.append("步骤 ").append(i).append("：执行检查、记录结果并在失败时回滚。\n");
            text.append(longLine).append("\n");
        }
        return text.append("结束条件：所有步骤均已审计。\n").toString();
    }

    private byte[] pdfFixture(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 1; page <= pages; page++) {
                PDPage pdfPage = new PDPage();
                document.addPage(pdfPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdfPage)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    stream.newLineAtOffset(60, 740);
                    stream.showText("POLICY-ID POLICY-" + page + " PAGE " + page);
                    stream.newLineAtOffset(0, -20);
                    stream.showText("This page contains policy clauses and approval responsibilities.");
                    stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docxFixture(int chapters) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= chapters; i++) {
            body.append("<w:p><w:r><w:t>手册章节 ").append(i).append("</w:t></w:r></w:p>")
                    .append("<w:p><w:r><w:t>责任人：运营团队；本章说明配置、检查、回滚和审计规则。</w:t></w:r></w:p>")
                    .append("<w:tbl><w:tr><w:tc><w:p><w:r><w:t>责任人</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>运营团队</w:t></w:r></w:p></w:tc></w:tr>")
                    .append("<w:tr><w:tc><w:p><w:r><w:t>状态</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>active</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
        }
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body + "</w:body></w:document>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "[Content_Types].xml", contentTypes);
            put(zip, "_rels/.rels", relationships);
            put(zip, "word/document.xml", documentXml);
            zip.finish();
            return output.toByteArray();
        }
    }

    private void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
