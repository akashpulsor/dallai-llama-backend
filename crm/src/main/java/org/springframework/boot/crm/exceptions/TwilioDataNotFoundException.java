package org.springframework.boot.crm.exceptions;

public class TwilioDataNotFoundException extends RuntimeException {
    public TwilioDataNotFoundException(String message) {
        super(message);
    }
}
