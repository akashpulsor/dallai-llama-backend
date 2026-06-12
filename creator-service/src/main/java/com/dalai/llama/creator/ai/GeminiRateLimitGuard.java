package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.function.Supplier;

@Component
public class GeminiRateLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(GeminiRateLimitGuard.class);

    private final CreatorProperties properties;
    private final Object requestMonitor = new Object();
    private volatile long cooldownUntilEpochMs = 0;
    private long nextRequestEpochMs = 0;

    public GeminiRateLimitGuard(CreatorProperties properties) {
        this.properties = properties;
    }

    public <T> T execute(String operation, String model, Supplier<T> supplier) {
        int maxAttempts = maxAttempts();
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            waitForLocalPermit(operation, model);
            try {
                return supplier.get();
            } catch (RuntimeException ex) {
                lastException = ex;
                WebClientResponseException responseException = webClientResponseException(ex);
                if (responseException != null) {
                    logProviderResponseFailure(operation, model, attempt, maxAttempts, responseException);
                }
                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    throw providerUnavailableException(operation, model, ex);
                }

                long delayMs = retryDelayMs(responseException, attempt);
                if (responseException != null && responseException.getStatusCode().value() == 429) {
                    delayMs = applyRateLimitCooldown(responseException, delayMs);
                }
                if (delayMs > maxLocalWaitMs()) {
                    log.warn(
                            "Gemini provider retry skipped because cooldown is longer than local wait operation={} model={} backend={} attempt={}/{} retryDelayMs={} maxLocalWaitMs={}",
                            operation,
                            model,
                            backend(),
                            attempt,
                            maxAttempts,
                            delayMs,
                            maxLocalWaitMs()
                    );
                    throw providerUnavailableException(operation, model, ex);
                }
                log.warn(
                        "Retrying Gemini provider call operation={} model={} backend={} attempt={}/{} retryDelayMs={} errorType={} errorMessage={}",
                        operation,
                        model,
                        backend(),
                        attempt,
                        maxAttempts,
                        delayMs,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                sleep(delayMs, "Gemini provider retry");
            }
        }
        throw lastException == null
                ? new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini provider failed without an exception.")
                : providerUnavailableException(operation, model, lastException);
    }

    private void waitForLocalPermit(String operation, String model) {
        long maxLocalWaitMs = maxLocalWaitMs();
        synchronized (requestMonitor) {
            long now = System.currentTimeMillis();
            long waitUntil = Math.max(cooldownUntilEpochMs, nextRequestEpochMs);
            long waitMs = Math.max(0, waitUntil - now);
            if (waitMs > maxLocalWaitMs) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Gemini is rate limited. Retry after about " + seconds(waitMs) + " seconds."
                );
            }
            if (waitMs > 0) {
                log.info(
                        "Waiting before Gemini provider call operation={} model={} backend={} waitMs={}",
                        operation,
                        model,
                        backend(),
                        waitMs
                );
                sleep(waitMs, "Gemini local rate limit wait");
                now = System.currentTimeMillis();
            }
            long minIntervalMs = Math.max(0, properties.getAi().getGeminiRequestMinIntervalMs());
            if (minIntervalMs > 0) {
                nextRequestEpochMs = now + minIntervalMs;
            }
        }
    }

    private RuntimeException providerUnavailableException(String operation, String model, RuntimeException ex) {
        WebClientResponseException responseException = webClientResponseException(ex);
        if (responseException != null && responseException.getStatusCode().value() == 429) {
            long cooldownMs = applyRateLimitCooldown(responseException, 0);
            String providerMessage = providerErrorMessage(responseException);
            return new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Gemini rate limit/quota exhausted for " + operation + ". Retry after about "
                            + seconds(cooldownMs) + " seconds. Provider said: " + providerMessage,
                    ex
            );
        }
        if (responseException != null && responseException.getStatusCode().is5xxServerError()) {
            return new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Gemini provider failed for " + operation + " with " + responseException.getStatusCode()
                            + " on model " + model + ".",
                    ex
            );
        }
        return ex;
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return isRetryableStatus(responseException.getStatusCode());
            }
            if (current instanceof ResponseStatusException responseStatusException) {
                return isRetryableStatus(responseStatusException.getStatusCode());
            }
            if (current instanceof WebClientRequestException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && isRetryableMessage(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isRetryableStatus(HttpStatusCode statusCode) {
        if (statusCode == null) {
            return false;
        }
        int value = statusCode.value();
        return value == 408 || value == 429 || statusCode.is5xxServerError();
    }

    private boolean isRetryableMessage(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("too many requests")
                || normalized.contains("rate limit")
                || normalized.contains("resource_exhausted")
                || normalized.contains("quota")
                || normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused");
    }

    private long retryDelayMs(WebClientResponseException responseException, int failedAttempt) {
        long retryAfterMs = responseException == null ? 0 : retryAfterDelayMs(responseException);
        long baseDelayMs = Math.max(0, properties.getAi().getGeminiRetryBackoffMs());
        long multiplier = 1L << Math.min(8, Math.max(0, failedAttempt - 1));
        long exponentialDelayMs = baseDelayMs > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : baseDelayMs * multiplier;
        long delayMs = Math.max(retryAfterMs, exponentialDelayMs);
        if (responseException != null && responseException.getStatusCode().value() == 429) {
            delayMs = Math.max(delayMs, rateLimitCooldownMs());
        }
        return Math.min(delayMs, retryMaxDelayMs());
    }

    private long applyRateLimitCooldown(WebClientResponseException responseException, long fallbackDelayMs) {
        long retryAfterMs = responseException == null ? 0 : retryAfterDelayMs(responseException);
        long cooldownMs = Math.max(Math.max(fallbackDelayMs, retryAfterMs), rateLimitCooldownMs());
        cooldownMs = Math.min(cooldownMs, rateLimitMaxCooldownMs());
        long until = System.currentTimeMillis() + cooldownMs;
        if (until > cooldownUntilEpochMs) {
            cooldownUntilEpochMs = until;
        }
        return Math.max(0, cooldownUntilEpochMs - System.currentTimeMillis());
    }

    private long retryAfterDelayMs(WebClientResponseException responseException) {
        String retryAfter = responseException.getHeaders().getFirst("Retry-After");
        if (retryAfter == null || retryAfter.isBlank()) {
            return 0;
        }
        String trimmed = retryAfter.trim();
        try {
            return Math.max(0, Duration.ofSeconds(Long.parseLong(trimmed)).toMillis());
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Duration.between(Instant.now(), retryAt).toMillis());
            } catch (DateTimeParseException ignoredDate) {
                return 0;
            }
        }
    }

    private WebClientResponseException webClientResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    private void logProviderResponseFailure(
            String operation,
            String model,
            int attempt,
            int maxAttempts,
            WebClientResponseException responseException
    ) {
        log.warn(
                "Gemini provider HTTP failure operation={} model={} backend={} attempt={}/{} statusCode={} retryAfterMs={} rawErrorBody={}",
                operation,
                model,
                backend(),
                attempt,
                maxAttempts,
                responseException.getStatusCode().value(),
                retryAfterDelayMs(responseException),
                truncate(responseException.getResponseBodyAsString(), 4000)
        );
    }

    private String providerErrorMessage(WebClientResponseException responseException) {
        String body = responseException.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return responseException.getStatusText();
        }
        return truncate(body.replaceAll("\\s+", " ").trim(), 700);
    }

    private void sleep(long delayMs, String label) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " interrupted.", ex);
        }
    }

    private int maxAttempts() {
        return Math.max(1, properties.getAi().getGeminiMaxAttempts());
    }

    private long rateLimitCooldownMs() {
        return Math.max(1000, properties.getAi().getGeminiRateLimitCooldownMs());
    }

    private long rateLimitMaxCooldownMs() {
        return Math.max(rateLimitCooldownMs(), properties.getAi().getGeminiRateLimitMaxCooldownMs());
    }

    private long maxLocalWaitMs() {
        return Math.max(0, properties.getAi().getGeminiRateLimitMaxLocalWaitMs());
    }

    private long retryMaxDelayMs() {
        return Math.max(1000, properties.getAi().getGeminiRetryMaxDelayMs());
    }

    private long seconds(long ms) {
        return Math.max(1, Math.round(ms / 1000.0d));
    }

    private String backend() {
        return properties.getAi().getGoogleGenaiBackend();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
    }
}
