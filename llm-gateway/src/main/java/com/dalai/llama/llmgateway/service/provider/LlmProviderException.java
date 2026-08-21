package com.dalai.llama.llmgateway.service.provider;

/**
 * Uniform provider-error contract every adapter maps into, so the orchestration layer can
 * decide retry/dead-letter behaviour without knowing each provider's own error shape.
 * {@code retryable} mirrors doc §13: 5xx/timeouts are retryable, 4xx auth/moderation are not.
 * v1 does not yet act on this distinction (no retry loop or DLQ is wired up), but adapters
 * report it correctly now so that behaviour is a pure orchestration-layer addition later.
 */
public class LlmProviderException extends RuntimeException {

    private final boolean retryable;

    public LlmProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
