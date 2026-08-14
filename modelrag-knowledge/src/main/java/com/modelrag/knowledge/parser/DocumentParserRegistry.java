package com.modelrag.knowledge.parser;

import java.util.List;
import org.springframework.stereotype.Component;

/** Extension point for adding file formats without changing the ingestion service. */
@Component
public class DocumentParserRegistry {
    private final List<DocumentParser> parsers;

    public DocumentParserRegistry() {
        this(TextDocumentParsers.defaults());
    }

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
    }

    public DocumentParser forFile(String fileName) {
        String safe = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        return parsers.stream().filter(parser -> parser.supports(safe)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("只支持 PDF、DOCX、MD、TXT 文件"));
    }
}
