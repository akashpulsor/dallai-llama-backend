package org.springframework.boot.crm.exceptions;

public class UserNotFoundException   extends  RuntimeException{
    public UserNotFoundException(String message) {
        super(message);
    }
}
