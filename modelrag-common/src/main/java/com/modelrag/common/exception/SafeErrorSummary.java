package com.modelrag.common.exception;

/** Converts failures to bounded persistence/API-safe text while detailed causes stay in protected logs. */
public final class SafeErrorSummary {
    private static final int MAX_LENGTH = 500;

    private SafeErrorSummary() {}

    public static String of(Throwable error) {
        if (error == null) return "处理失败 [Unknown]";
        if (error instanceof ModelRagException modelRag) {
            return limit(modelRag.getMessage() == null || modelRag.getMessage().isBlank()
                    ? "处理失败 [" + modelRag.errorCode().name() + "]" : modelRag.getMessage());
        }
        if (error instanceof IllegalArgumentException) {
            String message = error.getMessage();
            return limit(message == null || message.isBlank() ? "输入或内容不合法" : message);
        }
        return "依赖处理失败 [" + safeType(error.getClass().getSimpleName()) + "]";
    }

    private static String safeType(String value) {
        String safe = value == null ? "Unknown" : value.replaceAll("[^A-Za-z0-9_$]", "");
        return safe.isBlank() ? "Unknown" : safe;
    }

    private static String limit(String value) {
        String safe = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return safe.length() <= MAX_LENGTH ? safe : safe.substring(0, MAX_LENGTH);
    }
}
