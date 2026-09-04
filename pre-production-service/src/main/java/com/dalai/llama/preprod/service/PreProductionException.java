package com.dalai.llama.preprod.service;

import org.springframework.http.HttpStatus;

public class PreProductionException extends RuntimeException {

    private final HttpStatus status;

    public PreProductionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** Preserves the real root exception (e.g. a WebClientResponseException) as the Java cause
     * chain -- without this, logging the thrown PreProductionException only shows where THIS
     * wrapper was constructed, not what actually failed underneath it. */
    public PreProductionException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static PreProductionException notFound(String message) {
        return new PreProductionException(HttpStatus.NOT_FOUND, message);
    }

    public static PreProductionException badRequest(String message) {
        return new PreProductionException(HttpStatus.BAD_REQUEST, message);
    }

    public static PreProductionException conflict(String message) {
        return new PreProductionException(HttpStatus.CONFLICT, message);
    }
    public static PreProductionException paymentRequired(String message) {
        return new PreProductionException(HttpStatus.PAYMENT_REQUIRED, message);
    }

    public static PreProductionException upstream(String message) {
        return new PreProductionException(HttpStatus.BAD_GATEWAY, message);
    }

    public static PreProductionException upstream(String message, Throwable cause) {
        return new PreProductionException(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
