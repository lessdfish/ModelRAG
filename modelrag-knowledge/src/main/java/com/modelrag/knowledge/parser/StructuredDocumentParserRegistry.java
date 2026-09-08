package com.modelrag.knowledge.parser;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Registry for additive V2 structured parsers. */
@Component
public class StructuredDocumentParserRegistry {
    private final List<StructuredDocumentParser> parsers;

    public StructuredDocumentParserRegistry() {
        this(StructuredDocumentParsers.defaults());
    }

    public StructuredDocumentParserRegistry(List<StructuredDocumentParser> parsers) {
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
    }

    public StructuredDocumentParser forFile(String fileName) {
        String safe = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return parsers.stream().filter(parser -> parser.supports(safe)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V2 只支持 PDF、DOCX、MD、TXT 文件"));
    }

    public List<StructuredDocumentParser> parsers() { return parsers; }
}
