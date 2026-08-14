package com.modelrag.knowledge.service;

import com.modelrag.common.event.DocumentUploadedEvent;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.DocumentParserRegistry;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Streams uploads to object storage before parsing; PostgreSQL receives metadata only. */
@Service
public class DocumentService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);
    private final KnowledgeStore store;
    private final ObjectStorageService storage;
    private final ApplicationEventPublisher events;
    private final ParseLimits parseLimits;
    private final long maxFileSize;
    private final Duration parseTimeout;
    private final DocumentParserRegistry parsers;
    private final java.util.List<DocumentSafetyScanner> scanners;

    public DocumentService(KnowledgeStore store, ObjectStorageService storage, ApplicationEventPublisher events,
            long maxPages, int maxExtractedChars, long maxFileSize) {
        this(store, storage, events, maxPages, maxExtractedChars, maxFileSize, 120,
                new DocumentParserRegistry(), java.util.List.of(new BasicDocumentSafetyScanner()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentService(KnowledgeStore store, ObjectStorageService storage, ApplicationEventPublisher events,
            @Value("${modelrag.ingestion.max-pages:1000}") long maxPages,
            @Value("${modelrag.ingestion.max-extracted-chars:5000000}") int maxExtractedChars,
            @Value("${modelrag.ingestion.max-file-size-bytes:52428800}") long maxFileSize,
            @Value("${modelrag.ingestion.parse-timeout-seconds:120}") long parseTimeoutSeconds,
            DocumentParserRegistry parsers, java.util.List<DocumentSafetyScanner> scanners) {
        this.store = store;
        this.storage = storage;
        this.events = events;
        this.parseLimits = new ParseLimits(maxPages, maxExtractedChars);
        this.maxFileSize = maxFileSize;
        this.parseTimeout = Duration.ofSeconds(Math.max(1, Math.min(600, parseTimeoutSeconds)));
        this.parsers = parsers;
        this.scanners = scanners == null ? java.util.List.of() : java.util.List.copyOf(scanners);
    }

    public Document upload(long datasetId, MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
        return upload(datasetId, file.getOriginalFilename(), file.getContentType(), file.getSize(),
                file.getInputStream());
    }

    public Document upload(long datasetId, String originalFileName, String declaredContentType, long declaredSize,
            InputStream content) throws Exception {
        if (content == null || declaredSize == 0) throw new IllegalArgumentException("上传文件不能为空");
        if (declaredSize > maxFileSize) throw new IllegalArgumentException("文件超过大小上限");

        String fileName = safeFileName(originalFileName);
        String mimeType = normalizeContentType(declaredContentType);
        var parser = parsers.forFile(fileName);
        Path temporary = Files.createTempFile("modelrag-upload-", ".bin");
        String sourceKey = null;
        String artifactKey = null;
        String uploadId = java.util.UUID.randomUUID().toString();
        boolean metadataSaved = false;
        try {
            String hash;
            try (InputStream input = content) {
                hash = copyAndHash(input, temporary);
            }
            validateFileSignature(temporary, fileName, mimeType);
            for (DocumentSafetyScanner scanner : scanners) {
                scanner.scan(temporary, fileName, mimeType, maxFileSize);
            }

            sourceKey = "datasets/" + datasetId + "/documents/" + hash + "/uploads/" + uploadId
                    + "/source." + extension(fileName);
            try (InputStream source = Files.newInputStream(temporary)) {
                storage.put(sourceKey, source, Files.size(temporary), mimeType);
            }

            Path artifact = Files.createTempFile("modelrag-artifact-", ".txt");
            try {
                parseWithTimeout(parser, temporary, artifact);
                if (!hasExtractedText(artifact)) throw new IllegalArgumentException("文档未解析出可索引文本");
                String normalizedContentHash;
                try (InputStream artifactHash = Files.newInputStream(artifact)) {
                    normalizedContentHash = digest(artifactHash);
                }
                artifactKey = "datasets/" + datasetId + "/documents/" + hash + "/uploads/" + uploadId
                        + "/artifact.txt";
                try (InputStream artifactInput = Files.newInputStream(artifact)) {
                    storage.put(artifactKey, artifactInput, Files.size(artifact), "text/plain; charset=utf-8");
                }
                Document document = store.addDocument(datasetId, fileName, type(fileName), hash, null,
                        sourceKey, artifactKey, normalizedContentHash);
                metadataSaved = true;
                events.publishEvent(new DocumentUploadedEvent(this, document.id(), datasetId));
                return document;
            } finally {
                Files.deleteIfExists(artifact);
            }
        } catch (Exception error) {
            if (!metadataSaved) {
                deleteQuietly(artifactKey);
                deleteQuietly(sourceKey);
            }
            throw error;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void deleteObjects(Document document) {
        if (document == null) return;
        try {
            delete(document.artifactObjectKey());
            delete(document.sourceObjectKey());
        } catch (Exception error) {
            throw new IllegalStateException("文档对象存储清理失败，可安全重试", error);
        }
    }

    private void deleteQuietly(String objectKey) {
        try { delete(objectKey); }
        catch (Exception error) {
            // No database fact exists yet; cleanup remains best-effort, but the orphan must be observable.
            LOG.warn("Unable to roll back uploaded object: objectKey={}", objectKey, error);
        }
    }

    private void delete(String objectKey) throws Exception {
        if (objectKey != null && !objectKey.isBlank()) storage.delete(objectKey);
    }

    private void parseWithTimeout(com.modelrag.knowledge.parser.DocumentParser parser, Path source, Path artifact)
            throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            try (Writer output = Files.newBufferedWriter(artifact, StandardCharsets.UTF_8)) {
                parser.parseTo(source, parseLimits, output);
            }
            return null;
        });
        Thread worker = Thread.ofVirtual().name("modelrag-document-parser").start(task);
        try {
            task.get(parseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw new IllegalArgumentException("文档解析超过 " + parseTimeout.toSeconds() + " 秒上限", error);
        } catch (InterruptedException error) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文档解析被中断", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("文档解析失败", cause);
        } finally {
            if (!task.isDone()) worker.interrupt();
        }
    }

    private boolean hasExtractedText(Path artifact) throws Exception {
        try (Reader reader = Files.newBufferedReader(artifact, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    if (!Character.isWhitespace(buffer[index])) return true;
                }
            }
            return false;
        }
    }

    private String copyAndHash(InputStream input, Path destination) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxFileSize) throw new IllegalArgumentException("文件超过大小上限");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private String digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        return hex(digest.digest());
    }

    private void validateFileSignature(Path source, String fileName, String declaredContentType) throws Exception {
        String extension = extension(fileName);
        String mime = normalizeContentType(declaredContentType);
        if (!isAllowedMime(extension, mime)) {
            throw new IllegalArgumentException("文件扩展名与声明 MIME 类型不一致");
        }

        byte[] header;
        try (InputStream input = Files.newInputStream(source)) {
            header = input.readNBytes(512);
        }
        if ("pdf".equals(extension) && !startsWith(header, new byte[] {'%', 'P', 'D', 'F', '-'})) {
            throw new IllegalArgumentException("PDF 文件魔数校验失败");
        }
        if ("docx".equals(extension) && !startsWith(header, new byte[] {'P', 'K', 3, 4})) {
            throw new IllegalArgumentException("DOCX 文件魔数校验失败");
        }
        if (("md".equals(extension) || "txt".equals(extension)) && !isSupportedText(header)) {
            throw new IllegalArgumentException("文本文件内容不是有效 UTF-8/UTF-16 文本");
        }
    }

    private boolean isAllowedMime(String extension, String mime) {
        return switch (extension) {
            case "pdf" -> Set.of("application/pdf", "application/octet-stream").contains(mime);
            case "docx" -> Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip", "application/octet-stream").contains(mime);
            case "md" -> Set.of("text/markdown", "text/plain", "text/x-markdown", "application/octet-stream")
                    .contains(mime);
            case "txt" -> Set.of("text/plain", "application/octet-stream").contains(mime);
            default -> false;
        };
    }

    private String normalizeContentType(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    static boolean isSupportedText(byte[] value) {
        if (value.length >= 2 && ((value[0] == (byte) 0xff && value[1] == (byte) 0xfe)
                || (value[0] == (byte) 0xfe && value[1] == (byte) 0xff))) return true;
        for (byte item : value) {
            if (item == 0) return false;
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // The signature probe is only a prefix. A valid multi-byte code point may straddle byte 512,
        // so decode it as a non-final chunk while still rejecting malformed bytes inside the prefix.
        var result = decoder.decode(ByteBuffer.wrap(value), java.nio.CharBuffer.allocate(value.length), false);
        return !result.isError();
    }

    private String safeFileName(String value) {
        String name = value == null ? "upload.txt" : Path.of(value).getFileName().toString().trim();
        if (name.isBlank() || name.contains("..")) throw new IllegalArgumentException("文件名不合法");
        return name;
    }

    private String type(String name) { return extension(name).toUpperCase(Locale.ROOT); }

    private String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "bin" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String contentType(MultipartFile file) {
        return normalizeContentType(file.getContentType());
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
