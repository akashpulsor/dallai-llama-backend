package org.springframework.boot.crm.exceptions;

public class CallLogNotFoundException  extends RuntimeException{

    public CallLogNotFoundException(String message) {
        super(message);
    }
}
