package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
@ConditionalOnProperty(prefix = "creator.weekly-ideas.scheduler", name = "enabled", havingValue = "true")
public class WeeklyIdeaTagScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyIdeaTagScheduler.class);
    private static final long SCHEDULER_LOCK_KEY = 916_202_606_090_001L;

    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final WeeklyIdeaTagService weeklyIdeaTagService;

    public WeeklyIdeaTagScheduler(
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            WeeklyIdeaTagService weeklyIdeaTagService
    ) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.weeklyIdeaTagService = weeklyIdeaTagService;
    }

    @Scheduled(
            fixedDelayString = "${creator.weekly-ideas.scheduler.fixed-delay-ms:1296000000}",
            initialDelayString = "${creator.weekly-ideas.scheduler.initial-delay-ms:60000}"
    )
    public void refreshWeeklyIdeas() {
        runWithRetry(
                "Weekly creator idea tag scheduler",
                Math.max(0, properties.getWeeklyIdeas().getScheduler().getRetryCount()),
                Math.max(0, properties.getWeeklyIdeas().getScheduler().getRetryBackoffMs()),
                () -> {
                    String label = "Weekly creator idea tag scheduler";
                    long freshnessMs = Math.max(0, properties.getWeeklyIdeas().getScheduler().getFixedDelayMs());
                    runWithSchedulerLock(label, () -> {
                        log.info("{} fired freshnessMs={}", label, freshnessMs);
                        weeklyIdeaTagService.refreshIfStale(
                                "system",
                                "system",
                                "SCHEDULED_WEEKLY",
                                Duration.ofMillis(freshnessMs)
                        );
                    });
                }
        );
    }

    private void runWithSchedulerLock(String label, Runnable action) {
        Boolean executed = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            if (!tryAdvisoryLock(connection)) {
                log.info("{} skipped because another creator-service pod is refreshing weekly idea tags", label);
                return false;
            }
            try {
                action.run();
                return true;
            } finally {
                unlockAdvisoryLock(connection);
            }
        });
        if (!Boolean.TRUE.equals(executed)) {
            return;
        }
    }

    private boolean tryAdvisoryLock(java.sql.Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, SCHEDULER_LOCK_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private void unlockAdvisoryLock(java.sql.Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, SCHEDULER_LOCK_KEY);
            statement.execute();
        }
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
