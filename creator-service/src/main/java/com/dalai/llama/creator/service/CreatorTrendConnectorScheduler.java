package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
@ConditionalOnProperty(prefix = "creator.trends.scheduler", name = "enabled", havingValue = "true")
public class CreatorTrendConnectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(CreatorTrendConnectorScheduler.class);

    private final CreatorProperties properties;
    private final SourceConnectorOrchestrationService orchestrationService;

    public CreatorTrendConnectorScheduler(
            CreatorProperties properties,
            SourceConnectorOrchestrationService orchestrationService
    ) {
        this.properties = properties;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(
            fixedDelayString = "${creator.trends.scheduler.fixed-delay-ms:1800000}",
            initialDelayString = "${creator.trends.scheduler.initial-delay-ms:60000}"
    )
    public void collectTrendSignals() {
        if (!properties.getTrends().getScheduler().isEnabled()) {
            log.info("Creator trend scheduler skipped because creator.trends.scheduler.enabled=false");
            return;
        }
        runWithRetry(
                "Creator trend scheduler",
                Math.max(0, properties.getTrends().getScheduler().getRetryCount()),
                Math.max(0, properties.getTrends().getScheduler().getRetryBackoffMs()),
                () -> {
                    log.info("Creator trend scheduler fired");
                    orchestrationService.collectOnce("SCHEDULED");
                }
        );
    }

    private void runWithRetry(String label, int retryCount, long retryBackoffMs, Runnable action) {
        int maxAttempts = Math.max(1, retryCount + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.run();
                if (attempt > 1) {
                    log.info("{} succeeded after retry attempt={}/{}", label, attempt, maxAttempts);
                }
                return;
            } catch (RuntimeException ex) {
                if (attempt >= maxAttempts) {
                    log.error("{} failed after attempts={} errorType={} errorMessage={}",
                            label, attempt, ex.getClass().getSimpleName(), ex.getMessage(), ex);
                    return;
                }
                RateLimitFailure rateLimitFailure = rateLimitFailure(ex);
                long delayMs = retryDelayMs(retryBackoffMs, attempt, rateLimitFailure);
                if (rateLimitFailure != null) {
                    log.warn("{} rate limited by AI provider attempt={}/{} statusCode={} retryAfterMs={} retryDelayMs={} errorType={} errorMessage={}",
                            label, attempt, maxAttempts, rateLimitFailure.statusCode(), rateLimitFailure.retryAfterMs(),
                            delayMs, ex.getClass().getSimpleName(), ex.getMessage());
                } else {
                    log.warn("{} failed attempt={}/{} retryDelayMs={} errorType={} errorMessage={}",
                            label, attempt, maxAttempts, delayMs, ex.getClass().getSimpleName(), ex.getMessage());
                }
                sleepBeforeRetry(delayMs, label);
            }
        }
    }

    private long retryDelayMs(long retryBackoffMs, int failedAttempt, RateLimitFailure rateLimitFailure) {
        long configuredDelayMs = retryDelayMs(retryBackoffMs, failedAttempt);
        if (rateLimitFailure == null || rateLimitFailure.retryAfterMs() <= 0) {
            return configuredDelayMs;
        }
        return Math.max(configuredDelayMs, rateLimitFailure.retryAfterMs());
    }

    private long retryDelayMs(long retryBackoffMs, int failedAttempt) {
        long multiplier = Math.max(1, failedAttempt);
        if (retryBackoffMs > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return retryBackoffMs * multiplier;
    }

    private void sleepBeforeRetry(long delayMs, String label) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " retry interrupted", ex);
        }
    }

    private RateLimitFailure rateLimitFailure(Throwable throwable) {
        WebClientResponseException responseException = webClientResponseException(throwable);
        if (responseException == null || responseException.getStatusCode().value() != 429) {
            return null;
        }
        return new RateLimitFailure(responseException.getStatusCode().value(), retryAfterDelayMs(responseException));
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

    private record RateLimitFailure(int statusCode, long retryAfterMs) {
    }
}
