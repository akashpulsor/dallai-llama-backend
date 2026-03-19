package com.dalai.llama.pbx.core.exception;


import java.util.UUID;

/**
 * Thrown when a tenant's channel limit is exceeded.
 *
 * Caught by GlobalExceptionHandler → 429 TOO_MANY_REQUESTS.
 * Kamailio receives 429 and sends SIP 486 Busy Here to the caller.
 */
public class ChannelLimitExceededException extends RuntimeException {

    public ChannelLimitExceededException(UUID tenantId, long current, int limit, String direction) {
        super(String.format("Tenant %s %s channel limit exceeded: %d/%d",
                tenantId, direction, current, limit));
    }

    public ChannelLimitExceededException(String message) {
        super(message);
    }
}