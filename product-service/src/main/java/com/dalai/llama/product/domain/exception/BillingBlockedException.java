package com.dalai.llama.product.domain.exception;

public class BillingBlockedException extends RuntimeException {
    public BillingBlockedException(String message) {
        super(message);
    }
}
