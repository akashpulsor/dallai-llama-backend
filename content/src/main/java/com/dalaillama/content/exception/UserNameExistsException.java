package com.dalaillama.content.exception;

public class UserNameExistsException  extends  RuntimeException {
    public UserNameExistsException(String message) {
        super(message);
    }
}