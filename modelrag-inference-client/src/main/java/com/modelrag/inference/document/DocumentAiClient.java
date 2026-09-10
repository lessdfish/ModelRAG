package com.modelrag.inference.document;

import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.ParsedDocument;
import java.nio.file.Path;
import java.time.Duration;

/** Stateless document-parse compute port. Java retains the source and persistence boundary. */
public interface DocumentAiClient {
    ParsedDocument parse(Path source, String logicalFileName, ParseLimits limits, Duration timeout);
}
