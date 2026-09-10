package com.modelrag.inference.client;

/** Safe failure raised by the Java transport without exposing response content or credentials. */
public class AiServiceException extends RuntimeException {
    private final int statusCode;
    private final boolean retryable;

    public AiServiceException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public AiServiceException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() { return statusCode; }
    public boolean retryable() { return retryable; }
}
