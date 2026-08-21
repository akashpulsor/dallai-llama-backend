package com.dalai.llama.postprod.service;

import org.springframework.http.HttpStatus;

public class PostProductionException extends RuntimeException {

    private final HttpStatus status;

    public PostProductionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static PostProductionException notFound(String message) {
        return new PostProductionException(HttpStatus.NOT_FOUND, message);
    }

    public static PostProductionException badRequest(String message) {
        return new PostProductionException(HttpStatus.BAD_REQUEST, message);
    }

    public static PostProductionException conflict(String message) {
        return new PostProductionException(HttpStatus.CONFLICT, message);
    }

    public static PostProductionException upstream(String message) {
        return new PostProductionException(HttpStatus.BAD_GATEWAY, message);
    }
}
