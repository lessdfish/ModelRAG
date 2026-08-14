package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.exception.SafeErrorSummary;
import org.junit.jupiter.api.Test;

class SafeErrorSummaryTest {
    @Test
    void preservesStableDomainErrorsButHidesProviderDetails() {
        assertEquals("文件超过 50 MiB", SafeErrorSummary.of(
                new BusinessException(ErrorCode.VALIDATION, "文件超过 50 MiB")));

        String safe = SafeErrorSummary.of(new IllegalStateException(
                "provider failed at https://secret.internal/v1?api_key=do-not-store"));
        assertEquals("依赖处理失败 [IllegalStateException]", safe);
        assertFalse(safe.contains("secret.internal"));
        assertFalse(safe.contains("do-not-store"));
    }
}
