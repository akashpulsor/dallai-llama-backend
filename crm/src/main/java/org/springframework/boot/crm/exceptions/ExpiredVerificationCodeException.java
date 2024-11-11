package org.springframework.boot.crm.exceptions;

public class ExpiredVerificationCodeException extends RuntimeException {

    public ExpiredVerificationCodeException(String message) {
        super(message);
    }
}
