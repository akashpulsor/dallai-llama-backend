package com.dalai.llama.videogen.service;

import org.springframework.http.HttpStatus;

public class VideoGenException extends RuntimeException {

    private final HttpStatus status;

    public VideoGenException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static VideoGenException notFound(String message) {
        return new VideoGenException(HttpStatus.NOT_FOUND, message);
    }

    public static VideoGenException badRequest(String message) {
        return new VideoGenException(HttpStatus.BAD_REQUEST, message);
    }

    public static VideoGenException conflict(String message) {
        return new VideoGenException(HttpStatus.CONFLICT, message);
    }

    public static VideoGenException upstream(String message) {
        return new VideoGenException(HttpStatus.BAD_GATEWAY, message);
    }
}
