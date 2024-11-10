package org.springframework.boot.crm.exceptions;

public class UnregisteredEmailException extends  RuntimeException{

    public UnregisteredEmailException(String  message) {
        super(message);
    }

}
