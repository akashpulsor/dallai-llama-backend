package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class StudioPolishProviderQueueService {

    private static final int LOCK_NAMESPACE = 0x43525051; // CRPQ
    private static final Duration QUEUE_STATUS_INTERVAL = Duration.ofSeconds(15);

    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public StudioPolishProviderQueueService(
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource
    ) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public ProviderQueueLease acquire(
            String provider,
            UUID jobId,
            UUID variantId,
            int shotNumber,
            Consumer<ProviderQueueStatus> statusConsumer
    ) {
        String normalizedProvider = normalizeProvider(provider);
        if (!properties.getAi().isStudioPolishProviderQueueEnabled()) {
            return ProviderQueueLease.noop(normalizedProvider);
        }

        QueueLimits limits = limitsFor(normalizedProvider);
        long usedToday = countProviderGenerationsLast24h(normalizedProvider);
        if (limits.maxGenerationsPerDay() > 0 && usedToday >= limits.maxGenerationsPerDay()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "%s Studio Polish daily provider cap reached: %d/%d generations in the last 24h. Increase provider tier or wait for the rolling window to open."
                            .formatted(label(normalizedProvider), usedToday, limits.maxGenerationsPerDay())
            );
        }

        if (limits.maxConcurrentGenerations() <= 0) {
            return ProviderQueueLease.noop(normalizedProvider);
        }

        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plusMillis(Math.max(1000, properties.getAi().getStudioPolishProviderQueueMaxWaitMs()));
        Instant lastStatusAt = Instant.EPOCH;
        int attempts = 0;
        while (Instant.now().isBefore(deadline)) {
            for (int slot = 0; slot < limits.maxConcurrentGenerations(); slot++) {
                Connection connection = null;
                try {
                    connection = dataSource.getConnection();
                    connection.setAutoCommit(true);
                    if (tryLock(connection, normalizedProvider, slot)) {
                        Map<String, Object> metadata = queueMetadata(
                                normalizedProvider,
                                jobId,
                                variantId,
                                shotNumber,
                                slot,
                                limits,
                                usedToday,
                                startedAt,
                                "SLOT_ACQUIRED"
                        );
                        return new ProviderQueueLease(this, normalizedProvider, slot, connection, metadata);
                    }
                    closeQuietly(connection);
                    connection = null;
                } catch (SQLException ex) {
                    closeQuietly(connection);
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not acquire Studio Polish provider queue slot.", ex);
                }
            }

            Instant now = Instant.now();
            if (statusConsumer != null && Duration.between(lastStatusAt, now).compareTo(QUEUE_STATUS_INTERVAL) >= 0) {
                lastStatusAt = now;
                statusConsumer.accept(new ProviderQueueStatus(queueMetadata(
                        normalizedProvider,
                        jobId,
                        variantId,
                        shotNumber,
                        -1,
                        limits,
                        usedToday,
                        startedAt,
                        "WAITING_FOR_SLOT"
                )));
            }
            attempts++;
            sleep(Math.max(250, properties.getAi().getStudioPolishProviderQueuePollMs()), normalizedProvider);
        }

        throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "%s Studio Polish queue wait timed out after %d attempts. Try again shortly or increase provider concurrency."
                        .formatted(label(normalizedProvider), attempts)
        );
    }

    private QueueLimits limitsFor(String provider) {
        return switch (provider) {
            case "runway" -> new QueueLimits(
                    Math.max(0, properties.getAi().getRunwayMaxConcurrentGenerations()),
                    Math.max(0, properties.getAi().getRunwayMaxGenerationsPerDay())
            );
            case "luma" -> new QueueLimits(
                    Math.max(0, properties.getAi().getLumaMaxConcurrentGenerations()),
                    Math.max(0, properties.getAi().getLumaMaxGenerationsPerDay())
            );
            case "decart" -> new QueueLimits(
                    Math.max(0, properties.getAi().getDecartMaxConcurrentGenerations()),
                    Math.max(0, properties.getAi().getDecartMaxGenerationsPerDay())
            );
            case "google_veo" -> new QueueLimits(
                    Math.max(0, properties.getAi().getGoogleVeoMaxConcurrentGenerations()),
                    Math.max(0, properties.getAi().getGoogleVeoMaxGenerationsPerDay())
            );
            default -> new QueueLimits(1, 0);
        };
    }

    private long countProviderGenerationsLast24h(String provider) {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from creator_shot_enhancement_variants
                where lower(coalesce(provider, '')) = ?
                  and created_at >= now() - interval '24 hours'
                  and (
                    provider_operation_id is not null
                    or status in ('VIDEO_RUNNING', 'VIDEO_READY', 'TIMELINE_APPLIED', 'FAILED')
                  )
                """,
                Long.class,
                provider
        );
        return count == null ? 0L : count;
    }

    private boolean tryLock(Connection connection, String provider, int slot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, slotKey(provider, slot));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private boolean unlock(Connection connection, String provider, int slot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, slotKey(provider, slot));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private int slotKey(String provider, int slot) {
        return Objects.hash("studio-polish-provider", normalizeProvider(provider), slot);
    }

    private Map<String, Object> queueMetadata(
            String provider,
            UUID jobId,
            UUID variantId,
            int shotNumber,
            int slot,
            QueueLimits limits,
            long usedToday,
            Instant queuedAt,
            String status
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", status);
        metadata.put("provider", provider);
        metadata.put("providerLabel", label(provider));
        metadata.put("jobId", jobId == null ? null : jobId.toString());
        metadata.put("variantId", variantId == null ? null : variantId.toString());
        metadata.put("shotNumber", shotNumber);
        metadata.put("slot", slot >= 0 ? slot + 1 : null);
        metadata.put("maxConcurrentGenerations", limits.maxConcurrentGenerations());
        metadata.put("usedGenerationsLast24h", usedToday);
        metadata.put("maxGenerationsPerDay", limits.maxGenerationsPerDay());
        metadata.put("queuedAt", OffsetDateTime.now().toString());
        metadata.put("waitSeconds", Math.max(0, Duration.between(queuedAt, Instant.now()).toSeconds()));
        metadata.put("queue", "postgres_advisory_lock");
        return metadata;
    }

    private String normalizeProvider(String provider) {
        String value = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (value.equals("veo") || value.equals("google") || value.equals("gemini_veo")) {
            return "google_veo";
        }
        if (value.equals("luma_modify_video") || value.equals("luma_ai")) {
            return "luma";
        }
        if (value.equals("runway_aleph") || value.equals("runwayml")) {
            return "runway";
        }
        if (value.equals("decart_vton") || value.equals("lucy_vton") || value.equals("lucy_vton_3")) {
            return "decart";
        }
        return value.isBlank() ? "google_veo" : value;
    }

    private String label(String provider) {
        return switch (provider) {
            case "runway" -> "Runway";
            case "luma" -> "Luma";
            case "decart" -> "Decart Lucy VTON";
            case "google_veo" -> "Google Veo";
            default -> "Studio Polish provider";
        };
    }

    private void sleep(long millis, String provider) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while queued for " + label(provider) + " Studio Polish slot.", ex);
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private record QueueLimits(int maxConcurrentGenerations, int maxGenerationsPerDay) {
    }

    public record ProviderQueueStatus(Map<String, Object> metadata) {
    }

    public static final class ProviderQueueLease implements AutoCloseable {
        private final StudioPolishProviderQueueService owner;
        private final String provider;
        private final int slot;
        private final Connection connection;
        private final Map<String, Object> metadata;

        private ProviderQueueLease(
                StudioPolishProviderQueueService owner,
                String provider,
                int slot,
                Connection connection,
                Map<String, Object> metadata
        ) {
            this.owner = owner;
            this.provider = provider;
            this.slot = slot;
            this.connection = connection;
            this.metadata = metadata == null ? Map.of() : metadata;
        }

        private static ProviderQueueLease noop(String provider) {
            return new ProviderQueueLease(null, provider, -1, null, Map.of(
                    "status", "QUEUE_DISABLED",
                    "provider", provider
            ));
        }

        public Map<String, Object> metadata() {
            return metadata;
        }

        @Override
        public void close() {
            if (owner == null || connection == null || slot < 0) {
                return;
            }
            try {
                owner.unlock(connection, provider, slot);
            } catch (SQLException ignored) {
            } finally {
                owner.closeQuietly(connection);
            }
        }
    }
}
