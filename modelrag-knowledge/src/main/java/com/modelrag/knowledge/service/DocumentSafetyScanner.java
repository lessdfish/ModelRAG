package com.modelrag.knowledge.service;

import java.nio.file.Path;

/** Pluggable upload safety boundary; scanners must fail closed. */
public interface DocumentSafetyScanner {
    void scan(Path source, String fileName, String declaredContentType, long maxFileSize) throws Exception;
}
