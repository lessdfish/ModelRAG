package com.modelrag.indexing.pipeline.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.modelrag.inference.document.DocumentAiClient;
import com.modelrag.inference.document.DocumentParseMode;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.parser.DocumentParseMetadata;
import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.ParsedDocument;
import com.modelrag.knowledge.parser.ParsedNode;
import com.modelrag.knowledge.parser.StructuredDocumentParser;
import com.modelrag.knowledge.parser.StructuredDocumentParserRegistry;
import com.modelrag.knowledge.service.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocumentParseStageTest {
    private static final ParseLimits LIMITS = new ParseLimits(10, 1_000);

    @Test
    void remoteFirstUsesDocumentAiForPdfButTextRemainsLocal() throws Exception {
        AtomicInteger localCalls = new AtomicInteger();
        AtomicInteger remoteCalls = new AtomicInteger();
        StructuredDocumentParser parser = parser(localCalls, "local");
        DocumentAiClient remote = (source, name, limits, timeout) -> {
            remoteCalls.incrementAndGet();
            return parsed("remote");
        };
        DocumentParseStage stage = stage(parser, remote, true, DocumentParseMode.REMOTE_FIRST,
                DocumentParseMode.LOCAL_FIRST);

        assertEquals("remote", stage.parse(document("policy.pdf"), version()).nodes().get(0).title());
        assertEquals("local", stage.parse(document("notes.md"), version()).nodes().get(0).title());
        assertEquals(1, remoteCalls.get());
        assertEquals(1, localCalls.get());
    }

    @Test
    void remoteFirstFallsBackToLocalWhenRemoteFails() throws Exception {
        StructuredDocumentParser parser = parser(new AtomicInteger(), "local");
        DocumentAiClient remote = (source, name, limits, timeout) -> {
            throw new IllegalStateException("remote unavailable");
        };
        DocumentParseStage stage = stage(parser, remote, true, DocumentParseMode.REMOTE_FIRST,
                DocumentParseMode.LOCAL_FIRST);

        assertEquals("local", stage.parse(document("policy.pdf"), version()).nodes().get(0).title());
    }

    @Test
    void remoteOnlyWithoutRemoteFailsClosed() {
        DocumentParseStage stage = stage(parser(new AtomicInteger(), "local"), null, true,
                DocumentParseMode.REMOTE_ONLY, DocumentParseMode.REMOTE_ONLY);

        assertThrows(IllegalStateException.class, () -> stage.parse(document("policy.pdf"), version()));
    }

    private DocumentParseStage stage(StructuredDocumentParser parser, DocumentAiClient remote, boolean enabled,
            DocumentParseMode pdfMode, DocumentParseMode docxMode) {
        return new DocumentParseStage(new StructuredDocumentParserRegistry(List.of(parser)),
                mock(ObjectStorageService.class), remote, enabled, pdfMode, docxMode, LIMITS, 10_000, 5);
    }

    private StructuredDocumentParser parser(AtomicInteger calls, String title) {
        return new StructuredDocumentParser() {
            @Override public boolean supports(String fileName) { return true; }
            @Override public ParsedDocument parse(Path source, String logicalFileName, ParseLimits limits) {
                calls.incrementAndGet();
                return parsed(title);
            }
            @Override public String name() { return "test"; }
            @Override public String version() { return "1"; }
        };
    }

    private ParsedDocument parsed(String title) {
        return new ParsedDocument(new DocumentParseMetadata("test", "1", Map.of()),
                List.of(new ParsedNode("root", null, NodeType.DOCUMENT, 0, 0, title, "body", true, Map.of())),
                List.of());
    }

    private Document document(String fileName) {
        return new Document(9, 7, fileName, "FILE", "hash", "inline content", "READY", null, 0);
    }

    private DocumentVersion version() {
        return new DocumentVersion(19, 9, 1, "hash", null, null, "hash", "test", "1",
                DocumentParseStatus.READY, Map.of(), Instant.now(), Instant.now());
    }
}
