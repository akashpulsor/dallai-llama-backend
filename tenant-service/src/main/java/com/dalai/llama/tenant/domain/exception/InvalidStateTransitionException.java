package com.dalai.llama.tenant.domain.exception;

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(String msg) {
        super(msg);
    }
}