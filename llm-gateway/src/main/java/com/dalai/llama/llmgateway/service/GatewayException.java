package com.dalai.llama.llmgateway.service;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {

    private final HttpStatus status;

    public GatewayException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public GatewayException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static GatewayException notFound(String message) {
        return new GatewayException(HttpStatus.NOT_FOUND, message);
    }

    public static GatewayException forbidden(String message) {
        return new GatewayException(HttpStatus.FORBIDDEN, message);
    }

    public static GatewayException rateLimited(String message) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static GatewayException badRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, message);
    }

    public static GatewayException conflict(String message) {
        return new GatewayException(HttpStatus.CONFLICT, message);
    }

    public static GatewayException paymentRequired(String message) {
        return new GatewayException(HttpStatus.PAYMENT_REQUIRED, message);
    }
}
