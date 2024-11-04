package org.springframework.boot.crm.exceptions;

public class LlmDataNotFoundException extends RuntimeException {

    public LlmDataNotFoundException(String message) {
        super(message);
    }
}
