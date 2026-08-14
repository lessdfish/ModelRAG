package com.modelrag.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DocumentSignatureTest {

    @Test
    void acceptsUtf8PrefixEndingInsideMultibyteCodePoint() {
        byte[] source = ("a".repeat(511) + "中").getBytes(StandardCharsets.UTF_8);
        assertTrue(DocumentService.isSupportedText(Arrays.copyOf(source, 512)));
    }

    @Test
    void rejectsMalformedUtf8InsidePrefix() {
        assertFalse(DocumentService.isSupportedText(new byte[] {'a', (byte) 0xc3, 0x28}));
    }
}
