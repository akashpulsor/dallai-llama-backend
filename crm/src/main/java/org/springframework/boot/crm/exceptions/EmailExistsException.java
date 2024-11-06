package org.springframework.boot.crm.exceptions;

public class EmailExistsException extends  RuntimeException {
    public EmailExistsException(String  message) {
        super(message);
    }
}
