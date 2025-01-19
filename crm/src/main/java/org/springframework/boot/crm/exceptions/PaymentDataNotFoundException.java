package org.springframework.boot.crm.exceptions;

public class PaymentDataNotFoundException extends RuntimeException{

    public PaymentDataNotFoundException(String message) {
        super(message);
    }
}
