package org.springframework.boot.crm.exceptions;

public class PasswordMismatchException  extends  RuntimeException{

    public PasswordMismatchException(String  message) {
        super(message);
    }
}
