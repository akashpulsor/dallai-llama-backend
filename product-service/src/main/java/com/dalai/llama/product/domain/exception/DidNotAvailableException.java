package com.dalai.llama.product.domain.exception;

public class DidNotAvailableException extends RuntimeException {
    public DidNotAvailableException(String message) {
        super(message);
    }
}
