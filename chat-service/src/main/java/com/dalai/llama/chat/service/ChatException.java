package com.dalai.llama.chat.service;

import org.springframework.http.HttpStatus;

public class ChatException extends RuntimeException {

    private final HttpStatus status;

    public ChatException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ChatException notFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, message);
    }

    public static ChatException badRequest(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, message);
    }

    public static ChatException upstream(String message) {
        return new ChatException(HttpStatus.BAD_GATEWAY, message);
    }
}
