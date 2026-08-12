package com.modelrag.common.exception;
public enum ErrorCode { VALIDATION(40001), FORBIDDEN(40301), NOT_FOUND(40401), DUPLICATE_DOCUMENT(40901), LOW_CONFIDENCE(42201), RATE_LIMITED(42901), INTERNAL(50000); public final int code; ErrorCode(int code) { this.code = code; } }
