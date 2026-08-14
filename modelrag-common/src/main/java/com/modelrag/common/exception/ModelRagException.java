package com.modelrag.common.exception;

public class ModelRagException extends RuntimeException {
    private final ErrorCode errorCode;

    public ModelRagException(ErrorCode code, String message) {
        super(message);
        this.errorCode = code;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
