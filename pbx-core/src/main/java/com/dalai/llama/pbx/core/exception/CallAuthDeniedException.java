package com.dalai.llama.pbx.core.exception;



import lombok.Getter;

/**
 * Thrown when call authorization is denied.
 *
 * Carries a machine-readable code for Kamailio to act on:
 *   NO_TENANT           → 404 response (DID not mapped)
 *   TENANT_INACTIVE     → 403 response (suspended/deprovisioned)
 *   SUBSCRIPTION_INVALID → 403 response (billing issue)
 *   CHANNEL_LIMIT       → 429 response (too many concurrent calls)
 *   DNC_BLOCKED         → 403 response (number on Do-Not-Call list)
 *   INSUFFICIENT_BALANCE → 403 response (no credits)
 *
 * Caught by GlobalExceptionHandler → 403 FORBIDDEN with code in body.
 */
@Getter
public class CallAuthDeniedException extends RuntimeException {

    private final String code;

    public CallAuthDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }
}