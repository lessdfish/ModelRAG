package com.modelrag.common.exception;

public final class BusinessException extends ModelRagException {
    public BusinessException(ErrorCode code, String message) {
        super(code, message);
    }
}
