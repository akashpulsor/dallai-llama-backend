package com.dalai.llama.billing.domain.exception;

public class RatePlanNotFoundException extends RuntimeException {
    public RatePlanNotFoundException(String message) {
        super(message);
    }
}
