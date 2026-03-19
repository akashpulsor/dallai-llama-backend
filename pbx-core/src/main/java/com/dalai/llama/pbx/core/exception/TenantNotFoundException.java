package com.dalai.llama.pbx.core.exception;



import java.util.UUID;

/**
 * Thrown when a tenant cannot be resolved — all 3 cache tiers missed
 * (Redis, DB, HTTP to tenant-service) or DID has no tenant mapping.
 *
 * Caught by GlobalExceptionHandler → 404 NOT_FOUND.
 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String message) {
        super(message);
    }

    public TenantNotFoundException(UUID tenantId) {
        super("Tenant not found: " + tenantId);
    }

    public TenantNotFoundException(String field, String value) {
        super("Tenant not found by " + field + ": " + value);
    }
}