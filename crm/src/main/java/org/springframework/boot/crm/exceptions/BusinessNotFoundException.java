package org.springframework.boot.crm.exceptions;

public class BusinessNotFoundException extends RuntimeException {

    public BusinessNotFoundException(String message) {
        super(message);
    }
}
