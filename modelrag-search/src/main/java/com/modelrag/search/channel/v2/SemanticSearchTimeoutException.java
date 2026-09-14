package com.modelrag.search.channel.v2;

/** Semantic database work exceeded the remaining channel deadline. */
public class SemanticSearchTimeoutException extends RuntimeException {
    public SemanticSearchTimeoutException(String message) {
        super(message);
    }

    public SemanticSearchTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
