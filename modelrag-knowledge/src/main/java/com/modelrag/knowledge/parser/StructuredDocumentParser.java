package com.modelrag.knowledge.parser;

import java.nio.file.Path;

/** Additive V2 parser contract; the legacy DocumentParser remains unchanged. */
public interface StructuredDocumentParser {
    boolean supports(String fileName);

    ParsedDocument parse(Path source, String logicalFileName, ParseLimits limits) throws Exception;

    String name();

    String version();
}
