package com.dalai.llama.product.domain.exception;

public class DidwwApiException extends RuntimeException {
    public DidwwApiException(String message) {
        super(message);
    }

    public DidwwApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
