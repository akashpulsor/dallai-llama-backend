package org.springframework.boot.crm.exceptions;

public class LeadNotFoundException  extends RuntimeException{

    public LeadNotFoundException(String message) {
        super(message);
    }
}
