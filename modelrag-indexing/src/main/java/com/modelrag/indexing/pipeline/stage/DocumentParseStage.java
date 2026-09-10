package com.modelrag.indexing.pipeline.stage;

import com.modelrag.inference.document.DocumentAiClient;
import com.modelrag.inference.document.DocumentParseMode;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.ParsedDocument;
import com.modelrag.knowledge.parser.StructuredDocumentParser;
import com.modelrag.knowledge.parser.StructuredDocumentParserRegistry;
import com.modelrag.knowledge.service.ObjectStorageService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Reads the immutable source object into a bounded temp file before parsing outside any DB transaction. */
@Service
public class DocumentParseStage {
    private final StructuredDocumentParserRegistry parsers;
    private final ObjectStorageService storage;
    private final DocumentAiClient documentAi;
    private final boolean documentAiEnabled;
    private final DocumentParseMode pdfMode;
    private final DocumentParseMode docxMode;
    private final ParseLimits limits;
    private final long maxSourceBytes;
    private final Duration timeout;
    private final Duration documentAiTimeout;

    @Autowired
    public DocumentParseStage(StructuredDocumentParserRegistry parsers, ObjectStorageService storage,
            ObjectProvider<DocumentAiClient> documentAi,
            @Value("${modelrag.inference.document-ai.enabled:false}") boolean documentAiEnabled,
            @Value("${modelrag.inference.document-ai.pdf-mode:REMOTE_FIRST}") String pdfMode,
            @Value("${modelrag.inference.document-ai.docx-mode:LOCAL_FIRST}") String docxMode,
            @Value("${modelrag.inference.document-ai.timeout-seconds:120}") long documentAiTimeoutSeconds,
            @Value("${modelrag.ingestion.max-pages:1000}") long maxPages,
            @Value("${modelrag.ingestion.max-extracted-chars:5000000}") int maxExtractedChars,
            @Value("${modelrag.ingestion.max-file-size-bytes:52428800}") long maxSourceBytes,
            @Value("${modelrag.ingestion.parse-timeout-seconds:120}") long timeoutSeconds) {
        this(parsers, storage, documentAi == null ? null : documentAi.getIfAvailable(), documentAiEnabled,
                DocumentParseMode.parse(pdfMode, DocumentParseMode.REMOTE_FIRST),
                DocumentParseMode.parse(docxMode, DocumentParseMode.LOCAL_FIRST),
                new ParseLimits(maxPages, maxExtractedChars), maxSourceBytes, timeoutSeconds, documentAiTimeoutSeconds);
    }

    public DocumentParseStage(StructuredDocumentParserRegistry parsers, ObjectStorageService storage,
            ParseLimits limits, long maxSourceBytes, long timeoutSeconds) {
        this(parsers, storage, null, false, DocumentParseMode.LOCAL_ONLY, DocumentParseMode.LOCAL_ONLY,
                limits, maxSourceBytes, timeoutSeconds, timeoutSeconds);
    }

    public DocumentParseStage(StructuredDocumentParserRegistry parsers, ObjectStorageService storage,
            DocumentAiClient documentAi, boolean documentAiEnabled, DocumentParseMode pdfMode,
            DocumentParseMode docxMode, ParseLimits limits, long maxSourceBytes, long timeoutSeconds) {
        this(parsers, storage, documentAi, documentAiEnabled, pdfMode, docxMode, limits, maxSourceBytes,
                timeoutSeconds, timeoutSeconds);
    }

    public DocumentParseStage(StructuredDocumentParserRegistry parsers, ObjectStorageService storage,
            DocumentAiClient documentAi, boolean documentAiEnabled, DocumentParseMode pdfMode,
            DocumentParseMode docxMode, ParseLimits limits, long maxSourceBytes, long timeoutSeconds,
            long documentAiTimeoutSeconds) {
        this.parsers = parsers;
        this.storage = storage;
        this.documentAi = documentAi;
        this.documentAiEnabled = documentAiEnabled;
        this.pdfMode = pdfMode == null ? DocumentParseMode.LOCAL_ONLY : pdfMode;
        this.docxMode = docxMode == null ? DocumentParseMode.LOCAL_ONLY : docxMode;
        this.limits = limits;
        this.maxSourceBytes = Math.max(1, maxSourceBytes);
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(600, timeoutSeconds)));
        this.documentAiTimeout = Duration.ofSeconds(Math.max(1, Math.min(600, documentAiTimeoutSeconds)));
    }

    public ParsedDocument parse(Document document, DocumentVersion version) throws Exception {
        if (document == null || version == null) throw new IllegalArgumentException("文档和版本不能为空");
        StructuredDocumentParser parser = parsers.forFile(document.fileName());
        Path temporary = Files.createTempFile("modelrag-structured-", ".bin");
        try {
            copySource(document, version, temporary);
            return parseWithTimeout(parser, temporary, document.fileName());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void copySource(Document document, DocumentVersion version, Path destination) throws Exception {
        String sourceKey = version.sourceObjectKey();
        if (sourceKey == null || sourceKey.isBlank()) sourceKey = document.sourceObjectKey();
        if (sourceKey == null || sourceKey.isBlank()) {
            if (document.content() == null || document.content().isBlank()) {
                throw new IllegalArgumentException("V2 结构化解析缺少 source object");
            }
            byte[] content = document.content().getBytes(StandardCharsets.UTF_8);
            if (content.length > maxSourceBytes) throw new IllegalArgumentException("源文件超过大小上限");
            Files.write(destination, content, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        long copied = 0;
        try (InputStream input = storage.open(sourceKey); var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                copied += read;
                if (copied > maxSourceBytes) throw new IllegalArgumentException("源文件超过大小上限");
                output.write(buffer, 0, read);
            }
        }
    }

    private ParsedDocument parseWithTimeout(StructuredDocumentParser parser, Path source,
            String logicalFileName) throws Exception {
        FutureTask<ParsedDocument> task = new FutureTask<>(
                () -> parseRouted(parser, source, logicalFileName));
        Thread worker = Thread.ofVirtual().name("modelrag-structured-parser").start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw new IllegalArgumentException("V2 文档解析超过 " + timeout.toSeconds() + " 秒上限", error);
        } catch (InterruptedException error) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("V2 文档解析被中断", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("V2 文档解析失败", cause);
        } finally {
            if (!task.isDone()) worker.interrupt();
        }
    }

    private ParsedDocument parseRouted(StructuredDocumentParser parser, Path source, String logicalFileName)
            throws Exception {
        if (isLocalText(logicalFileName)) return parser.parse(source, logicalFileName, limits);
        DocumentParseMode mode = modeFor(logicalFileName);
        return switch (mode) {
            case LOCAL_ONLY -> parser.parse(source, logicalFileName, limits);
            case REMOTE_ONLY -> remoteParse(source, logicalFileName);
            case LOCAL_FIRST -> localFirst(parser, source, logicalFileName);
            case REMOTE_FIRST -> remoteFirst(parser, source, logicalFileName);
        };
    }

    private ParsedDocument localFirst(StructuredDocumentParser parser, Path source, String logicalFileName)
            throws Exception {
        try {
            return parser.parse(source, logicalFileName, limits);
        } catch (Exception localFailure) {
            if (!remoteAvailable()) throw localFailure;
            try {
                return remoteParse(source, logicalFileName);
            } catch (Exception ignored) {
                throw localFailure;
            }
        }
    }

    private ParsedDocument remoteFirst(StructuredDocumentParser parser, Path source, String logicalFileName)
            throws Exception {
        if (!remoteAvailable()) return parser.parse(source, logicalFileName, limits);
        try {
            return remoteParse(source, logicalFileName);
        } catch (Exception remoteFailure) {
            try {
                return parser.parse(source, logicalFileName, limits);
            } catch (Exception localFailure) {
                throw remoteFailure;
            }
        }
    }

    private ParsedDocument remoteParse(Path source, String logicalFileName) {
        if (!remoteAvailable()) throw new IllegalStateException("文档 AI 服务未启用");
        return documentAi.parse(source, logicalFileName, limits, documentAiTimeout);
    }

    private boolean remoteAvailable() {
        return documentAiEnabled && documentAi != null;
    }

    private DocumentParseMode modeFor(String logicalFileName) {
        String lower = logicalFileName == null ? "" : logicalFileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return documentAiEnabled ? pdfMode : DocumentParseMode.LOCAL_ONLY;
        if (lower.endsWith(".docx")) return documentAiEnabled ? docxMode : DocumentParseMode.LOCAL_ONLY;
        return DocumentParseMode.LOCAL_ONLY;
    }

    private boolean isLocalText(String logicalFileName) {
        String lower = logicalFileName == null ? "" : logicalFileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".txt");
    }
}
