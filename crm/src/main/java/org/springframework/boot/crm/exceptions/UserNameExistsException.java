package org.springframework.boot.crm.exceptions;

public class UserNameExistsException  extends  RuntimeException {
    public UserNameExistsException(String message) {
        super(message);
    }
}