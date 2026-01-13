package com.dalai.llama.tenant.domain.exception;

public class TenantAlreadyExistsException extends RuntimeException {
    public TenantAlreadyExistsException(String slug) {
        super("Tenant already exists: " + slug);
    }
}
