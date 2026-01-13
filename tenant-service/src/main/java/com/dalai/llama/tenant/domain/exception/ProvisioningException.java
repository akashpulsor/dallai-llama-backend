package com.dalai.llama.tenant.domain.exception;

public class ProvisioningException extends RuntimeException {
    public ProvisioningException(String message) {
        super(message);
    }
}
