package org.springframework.boot.crm.exceptions;

public class ToolExecutionException extends RuntimeException {
    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ToolExecutionException(String message) {
        super(message);
    }
}