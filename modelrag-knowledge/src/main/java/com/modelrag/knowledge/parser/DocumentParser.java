package com.modelrag.knowledge.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Writer;

/** Parser contract used by the streaming ingestion pipeline. */
public interface DocumentParser {
    boolean supports(String fileName);

    String parse(Path source, ParseLimits limits) throws Exception;

    /** Writes normalized text to the supplied sink so production ingestion need not build one large String. */
    default void parseTo(Path source, ParseLimits limits, Writer target) throws Exception {
        target.write(parse(source, limits));
    }

    default String parse(Path source) throws Exception {
        return parse(source, ParseLimits.defaults());
    }

    /** Compatibility helper for small unit fixtures; production ingestion never uses it. */
    default String parse(byte[] content) throws Exception {
        Path temporary = Files.createTempFile("modelrag-parser-", ".bin");
        try {
            Files.write(temporary, content);
            return parse(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    String name();
}
