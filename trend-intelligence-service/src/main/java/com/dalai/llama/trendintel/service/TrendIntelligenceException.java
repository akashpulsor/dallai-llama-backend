package com.dalai.llama.trendintel.service;

import org.springframework.http.HttpStatus;

public class TrendIntelligenceException extends RuntimeException {

    private final HttpStatus status;

    public TrendIntelligenceException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static TrendIntelligenceException notFound(String message) {
        return new TrendIntelligenceException(HttpStatus.NOT_FOUND, message);
    }

    public static TrendIntelligenceException badRequest(String message) {
        return new TrendIntelligenceException(HttpStatus.BAD_REQUEST, message);
    }

    public static TrendIntelligenceException upstream(String message) {
        return new TrendIntelligenceException(HttpStatus.BAD_GATEWAY, message);
    }
}
