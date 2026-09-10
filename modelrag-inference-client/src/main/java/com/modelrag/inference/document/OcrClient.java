package com.modelrag.inference.document;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Stateless OCR compute port. The Java caller controls source and page limits. */
public interface OcrClient {
    OcrResponse ocr(Path source, String contentType, List<String> languageHints, Integer pageNumber,
            Duration timeout);

    default OcrResponse ocr(Path source, String contentType, List<String> languageHints, int pageNumber,
            Duration timeout) {
        return ocr(source, contentType, languageHints, Integer.valueOf(pageNumber), timeout);
    }
}
