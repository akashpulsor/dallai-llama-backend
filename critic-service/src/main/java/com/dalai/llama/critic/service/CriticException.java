package com.dalai.llama.critic.service;

import org.springframework.http.HttpStatus;

public class CriticException extends RuntimeException {

    private final HttpStatus status;

    public CriticException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static CriticException notFound(String message) {
        return new CriticException(HttpStatus.NOT_FOUND, message);
    }

    public static CriticException badRequest(String message) {
        return new CriticException(HttpStatus.BAD_REQUEST, message);
    }

    public static CriticException upstream(String message) {
        return new CriticException(HttpStatus.BAD_GATEWAY, message);
    }
}
