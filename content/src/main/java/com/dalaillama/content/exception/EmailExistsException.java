package com.dalaillama.content.exception;

public class EmailExistsException extends  RuntimeException {
    public EmailExistsException(String  message) {
        super(message);
    }
}
