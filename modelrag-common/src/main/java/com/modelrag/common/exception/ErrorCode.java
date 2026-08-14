package com.modelrag.common.exception;

public enum ErrorCode {
    VALIDATION(40001), FORBIDDEN(40301), NOT_FOUND(40401), DUPLICATE_DOCUMENT(40901), DUPLICATE_OPERATION(40902),
    LOW_CONFIDENCE(42201), OCR_REQUIRED(42202), RATE_LIMITED(42901), DEPENDENCY_UNAVAILABLE(50301),
    INTERNAL(50000);
    public final int code;

    ErrorCode(int code) {
        this.code = code;
    }
}
