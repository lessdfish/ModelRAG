package com.modelrag.knowledge.splitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecursiveCharSplitterTest {
    @Test
    void readerWindowingKeepsDataWhenReadEndsAtChunkBoundary() throws Exception {
        String source = "0123456789".repeat(80);
        RecursiveCharSplitter splitter = new RecursiveCharSplitter();
        List<String> expected = new ArrayList<>();
        List<String> windows = new ArrayList<>();

        splitter.forEachWindow(source, 20, 4, 3, expected::addAll);
        splitter.forEachWindow(new ChunkedReader(source, 80), 20, 4, 3, windows::addAll);

        assertEquals(expected, windows);
    }

    @Test
    void markdownFenceIsNotSplitByOrdinaryTokenBoundaries() throws Exception {
        String source = "前置说明。\n```java\n" + "System.out.println(\"bounded code\");\n".repeat(8)
                + "```\n后置说明。";
        RecursiveCharSplitter splitter = new RecursiveCharSplitter();
        List<String> chunks = new ArrayList<>();

        splitter.forEachWindow(new ChunkedReader(source, 17), 30, 0, 2, chunks::addAll);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("```java")
                && chunk.contains("bounded code") && secondFence(chunk) >= 0));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.contains("```java") && secondFence(chunk) < 0));
    }

    private static int secondFence(String value) {
        int first = value.indexOf("```");
        return first < 0 ? -1 : value.indexOf("```", first + 3);
    }

    private static final class ChunkedReader extends Reader {
        private final Reader delegate;
        private final int maxRead;

        private ChunkedReader(String value, int maxRead) {
            this.delegate = new StringReader(value);
            this.maxRead = maxRead;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, Math.min(length, maxRead));
        }

        @Override public void close() throws IOException { delegate.close(); }
    }
}
