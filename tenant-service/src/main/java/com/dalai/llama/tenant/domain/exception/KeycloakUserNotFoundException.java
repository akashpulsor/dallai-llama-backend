package com.dalai.llama.tenant.domain.exception;

public class KeycloakUserNotFoundException extends RuntimeException {
    public KeycloakUserNotFoundException(String message) {
        super(message);
    }

    public KeycloakUserNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}