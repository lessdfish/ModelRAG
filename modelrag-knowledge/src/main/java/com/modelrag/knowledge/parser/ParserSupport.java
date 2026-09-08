package com.modelrag.knowledge.parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small shared helpers for local, bounded structured parsers. */
final class ParserSupport {
    private ParserSupport() { }

    static int tokenCount(String text) {
        if (text == null || text.isBlank()) return 0;
        double tokens = 0;
        for (int index = 0; index < text.length(); index++) {
            tokens += Character.UnicodeScript.of(text.charAt(index)) == Character.UnicodeScript.HAN ? 1 : .25;
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("解析内容哈希失败", error);
        }
    }

    static Map<String, Object> metadata(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index] != null && values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return result;
    }

    static void appendBounded(StringBuilder target, String value, int limit) {
        if (value == null || value.isEmpty()) return;
        if (target.length() + value.length() > limit) {
            throw new IllegalArgumentException("文档解析文本超过安全上限");
        }
        target.append(value);
    }

    static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String requireLogicalFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("逻辑文件名不能为空");
        }
        return fileName;
    }
}
