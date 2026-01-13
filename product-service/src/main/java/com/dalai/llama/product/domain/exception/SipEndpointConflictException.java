package com.dalai.llama.product.domain.exception;

public class SipEndpointConflictException extends RuntimeException {
    public SipEndpointConflictException(String message) {
        super(message);
    }
}
