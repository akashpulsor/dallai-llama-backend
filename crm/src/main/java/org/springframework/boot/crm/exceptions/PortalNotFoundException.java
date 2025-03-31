package org.springframework.boot.crm.exceptions;

public class PortalNotFoundException extends RuntimeException{

    public PortalNotFoundException(String message) {
        super(message);
    }
}
