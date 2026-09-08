package com.modelrag.knowledge.parser;

import java.util.List;

/** Default local Java parser set for the first structured-ingestion batch. */
final class StructuredDocumentParsers {
    private StructuredDocumentParsers() { }

    static List<StructuredDocumentParser> defaults() {
        return List.of(new MarkdownStructuredParser(), new PlainTextStructuredParser(),
                new DocxStructuredParser(), new PdfStructuredParser());
    }
}
