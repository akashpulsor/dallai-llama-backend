package com.dalai.llama.product.domain.exception;

public class EntitlementExceededException extends RuntimeException {
    public EntitlementExceededException(String message) {
        super(message);
    }
}
