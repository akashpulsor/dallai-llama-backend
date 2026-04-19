package com.dalai.llama.tenant.domain.exception;

public class KeycloakException extends RuntimeException {
    public KeycloakException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public KeycloakException(String msg) {
        super(msg);
    }
}
