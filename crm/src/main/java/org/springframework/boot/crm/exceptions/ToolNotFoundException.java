package org.springframework.boot.crm.exceptions;

public class ToolNotFoundException extends RuntimeException{

    public ToolNotFoundException(String message) {
        super(message);
    }
}
