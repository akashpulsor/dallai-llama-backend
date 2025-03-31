package org.springframework.boot.crm.exceptions;

// Custom Exception
    public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) {
        super(message);
    }


}
