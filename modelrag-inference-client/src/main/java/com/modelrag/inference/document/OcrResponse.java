package com.modelrag.inference.document;

import java.util.List;

/** Wire response from the bounded OCR endpoint. */
public record OcrResponse(List<OcrBlock> blocks) {
    public OcrResponse {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        if (blocks.size() > 10_000) throw new IllegalArgumentException("OCR block count exceeds limit");
    }
}
