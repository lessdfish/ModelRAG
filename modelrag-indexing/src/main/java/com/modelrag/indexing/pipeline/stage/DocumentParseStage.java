package com.modelrag.indexing.pipeline.stage;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.parser.DocumentParseMetadata;
import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.ParsedDocument;
import com.modelrag.knowledge.parser.StructuredDocumentParser;
import com.modelrag.knowledge.parser.StructuredDocumentParserRegistry;
import com.modelrag.knowledge.service.ObjectStorageService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Reads the immutable source object into a bounded temp file before parsing outside any DB transaction. */
@Service
public class DocumentParseStage {
    private final StructuredDocumentParserRegistry parsers;
    private final ObjectStorageService storage;
    private final ParseLimits limits;
    private final long maxSourceBytes;
    private final Duration timeout;

    @Autowired
    public DocumentParseStage(StructuredDocumentParserRegistry parsers, ObjectStorageService storage,
            @Value("${modelrag.ingestion.max-pages:1000}") long maxPages,
            @Value("${modelrag.ingestion.max-extracted-chars:5000000}") int maxExtractedChars,
            @Value("${modelrag.ingestion.max-file-size-bytes:52428800}") long maxSourceBytes,
            @Value("${modelrag.ingestion.parse-timeout-seconds:120}") long timeoutSeconds) {
        this(parsers, storage, new ParseLimits(maxPages, maxExtractedChars), maxSourceBytes, timeoutSeconds);
    }

    public DocumentParseStage(StructuredDocumentParserRegistry parsers, ObjectStorageService storage,
            ParseLimits limits, long maxSourceBytes, long timeoutSeconds) {
        this.parsers = parsers;
        this.storage = storage;
        this.limits = limits;
        this.maxSourceBytes = Math.max(1, maxSourceBytes);
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(600, timeoutSeconds)));
    }

    public ParsedDocument parse(Document document, DocumentVersion version) throws Exception {
        if (document == null || version == null) throw new IllegalArgumentException("文档和版本不能为空");
        StructuredDocumentParser parser = parsers.forFile(document.fileName());
        Path temporary = Files.createTempFile("modelrag-structured-", ".bin");
        try {
            copySource(document, version, temporary);
            return parseWithTimeout(parser, temporary);
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
            Files.writeString(destination, document.content(), java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        long copied = 0;
        try (InputStream input = storage.open(sourceKey); var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied += read;
                if (copied > maxSourceBytes) throw new IllegalArgumentException("源文件超过大小上限");
                output.write(buffer, 0, read);
            }
        }
    }

    private ParsedDocument parseWithTimeout(StructuredDocumentParser parser, Path source) throws Exception {
        FutureTask<ParsedDocument> task = new FutureTask<>(() -> parser.parse(source, limits));
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
}
