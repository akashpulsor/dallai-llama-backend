package org.springframework.boot.crm.exceptions;

public class PhoneNumberExistsException extends  RuntimeException{

    public PhoneNumberExistsException(String message) {
        super(message);
    }
}
