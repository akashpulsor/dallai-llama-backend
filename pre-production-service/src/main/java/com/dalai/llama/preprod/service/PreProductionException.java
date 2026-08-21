package com.dalai.llama.preprod.service;

import org.springframework.http.HttpStatus;

public class PreProductionException extends RuntimeException {

    private final HttpStatus status;

    public PreProductionException(HttpStatus status, String message) {
        super(message);
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

    public static PreProductionException upstream(String message) {
        return new PreProductionException(HttpStatus.BAD_GATEWAY, message);
    }
}
