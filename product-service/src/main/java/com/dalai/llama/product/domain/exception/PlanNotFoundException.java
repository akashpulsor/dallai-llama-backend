package com.dalai.llama.product.domain.exception;

public class PlanNotFoundException extends RuntimeException {
    public PlanNotFoundException(String code) {
        super("Plan not found: " + code);
    }
}
