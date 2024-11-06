package org.springframework.boot.crm.exceptions;

public class BusinessNotFoundException extends UserNotFoundException {

    public BusinessNotFoundException(String message) {
        super(message);
    }
}
