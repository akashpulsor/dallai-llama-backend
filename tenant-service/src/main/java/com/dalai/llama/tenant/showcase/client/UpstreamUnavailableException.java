package com.dalai.llama.tenant.showcase.client;

/** Another of our services (pre-production, creative-planning, billing) didn't answer. 503: try
 * again later, not the caller's fault. */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
