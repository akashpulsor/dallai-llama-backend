package org.springframework.boot.crm.exceptions;

public class ExternalServerException extends RuntimeException {
    public ExternalServerException(String message) {
        super(message);
    }
}
