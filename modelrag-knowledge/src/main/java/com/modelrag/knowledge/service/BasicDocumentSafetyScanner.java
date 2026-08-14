package com.modelrag.knowledge.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/** Size/archive limits implemented as the default scanner; ClamAV can be added as another bean. */
@Component
public class BasicDocumentSafetyScanner implements DocumentSafetyScanner {
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_NESTING_DEPTH = 3;
    private static final long MAX_EXPANSION_RATIO = 100;

    @Override
    public void scan(Path source, String fileName, String declaredContentType, long maxFileSize) throws Exception {
        if (Files.size(source) > maxFileSize) throw new IllegalArgumentException("文件超过大小上限");
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) return;
        Limits limits = new Limits(Math.max(maxFileSize, Math.min(200L * 1024 * 1024, maxFileSize * 4)));
        scanArchive(source, 0, limits, maxFileSize, true);
    }

    private void scanArchive(Path archive, int depth, Limits limits, long maxFileSize, boolean officeRoot)
            throws Exception {
        if (depth > MAX_NESTING_DEPTH) throw new IllegalArgumentException("压缩文档嵌套层级超过安全上限");
        boolean hasContentTypes = false;
        boolean hasDocument = false;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (entry.isDirectory()) continue;
                if (++limits.entries > MAX_ENTRIES) throw new IllegalArgumentException("压缩文档条目超过安全上限");
                String name = safeEntryName(entry.getName());
                hasContentTypes |= "[Content_Types].xml".equals(name);
                hasDocument |= "word/document.xml".equals(name);
                long declaredExpanded = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (declaredExpanded < 0 || compressedSize < 0 || declaredExpanded > maxFileSize
                        || compressedSize > maxFileSize
                        || expansionRatioExceeded(declaredExpanded, compressedSize)) {
                    throw new IllegalArgumentException("压缩文档条目或解压比例超过安全上限");
                }
                limits.add(declaredExpanded);
                if (nestedArchive(name, zip, entry)) {
                    if (depth == MAX_NESTING_DEPTH) {
                        throw new IllegalArgumentException("压缩文档嵌套层级超过安全上限");
                    }
                    Path nested = Files.createTempFile("modelrag-nested-archive-", ".zip");
                    try (InputStream input = zip.getInputStream(entry);
                            OutputStream output = Files.newOutputStream(nested)) {
                        copyBounded(input, output, maxFileSize);
                        scanArchive(nested, depth + 1, limits, maxFileSize, false);
                    } finally {
                        Files.deleteIfExists(nested);
                    }
                }
            }
        }
        if (officeRoot && (!hasContentTypes || !hasDocument)) {
            throw new IllegalArgumentException("DOCX 压缩包缺少 Office 文档必需部件");
        }
    }

    private boolean expansionRatioExceeded(long expanded, long compressed) {
        if (expanded <= 0) return false;
        if (compressed <= 0) return true;
        return expanded > Math.multiplyExact(compressed, MAX_EXPANSION_RATIO);
    }

    private String safeEntryName(String value) {
        String name = value == null ? "" : value.replace('\\', '/');
        if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")
                || java.util.Arrays.asList(name.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("压缩文档包含不安全路径");
        }
        return name;
    }

    private boolean nestedArchive(String name, ZipFile zip, ZipEntry entry) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") || lower.endsWith(".docx") || lower.endsWith(".xlsx")
                || lower.endsWith(".pptx")) return true;
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] magic = input.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K'
                    && ((magic[2] == 3 && magic[3] == 4) || (magic[2] == 5 && magic[3] == 6)
                    || (magic[2] == 7 && magic[3] == 8));
        }
    }

    private void copyBounded(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("嵌套压缩文档超过安全上限");
            output.write(buffer, 0, read);
        }
    }

    private static final class Limits {
        private final long maxExpanded;
        private long expanded;
        private int entries;

        private Limits(long maxExpanded) {
            this.maxExpanded = maxExpanded;
        }

        private void add(long bytes) {
            expanded = Math.addExact(expanded, Math.max(0, bytes));
            if (expanded > maxExpanded) throw new IllegalArgumentException("压缩文档解压后超过安全上限");
        }
    }
}
