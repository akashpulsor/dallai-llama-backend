package com.dalai.llama.creator.domain.entity;

import com.dalai.llama.creator.domain.ConnectorAuthType;
import com.dalai.llama.creator.domain.ConnectorCallMethod;
import com.dalai.llama.creator.domain.ConnectorFailurePolicy;
import com.dalai.llama.creator.domain.ConnectorParserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_source_connectors")
/**
 * Master data for one scheduler-callable trend signal source.
 *
 * A connector defines how to call a source, how often it may be called, how failure
 * should be handled, and how the response should be parsed.
 */
public class CreatorSourceConnector {

    /** Primary key for the source connector. */
    @Id
    private UUID id;

    /** Stable connector code used by scheduler and run logs. */
    @Column(nullable = false, unique = true, length = 96)
    private String code;

    /** Human-readable connector name. */
    @Column(name = "display_name", nullable = false, length = 180)
    private String displayName;

    /** Signal source platform code, for example reddit or google_trends. */
    @Column(name = "source_platform_code", nullable = false, length = 64)
    private String sourcePlatformCode;

    /** How the connector is called. */
    @Enumerated(EnumType.STRING)
    @Column(name = "call_method", nullable = false, length = 64)
    private ConnectorCallMethod callMethod;

    /** Authentication style required by the connector. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 64)
    private ConnectorAuthType authType;

    /** Parser needed for the response. */
    @Enumerated(EnumType.STRING)
    @Column(name = "parser_type", nullable = false, length = 64)
    private ConnectorParserType parserType;

    /** Base URL for network-based connectors. */
    @Column(name = "base_url", columnDefinition = "text")
    private String baseUrl;

    /** JSON request template rendered with category, locale, country, and keyword context. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_template", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestTemplate = new LinkedHashMap<>();

    /** JSON header template rendered before making a request. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers_template", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> headersTemplate = new LinkedHashMap<>();

    /** Maximum connector calls allowed per minute. */
    @Column(name = "rate_limit_per_minute", nullable = false)
    private Integer rateLimitPerMinute;

    /** Maximum connector calls allowed per day. */
    @Column(name = "rate_limit_per_day")
    private Integer rateLimitPerDay;

    /** Maximum connector calls allowed per month. */
    @Column(name = "monthly_call_limit")
    private Integer monthlyCallLimit;

    /** Per-call timeout in milliseconds. */
    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs;

    /** Number of retries after the first failed attempt. */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /** Delay between retries in milliseconds. */
    @Column(name = "retry_backoff_ms", nullable = false)
    private Integer retryBackoffMs;

    /** How scheduler handles failure. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_policy", nullable = false, length = 64)
    private ConnectorFailurePolicy failurePolicy;

    /** Consecutive failure threshold before cooling down this connector. */
    @Column(name = "circuit_breaker_failure_threshold", nullable = false)
    private Integer circuitBreakerFailureThreshold;

    /** Cooldown duration after circuit breaker trips. */
    @Column(name = "cooldown_seconds", nullable = false)
    private Integer cooldownSeconds;

    /** Whether scheduler is allowed to run this connector. */
    @Column(nullable = false)
    private boolean enabled;

    /** Whether this connector requires a configured secret/API key. */
    @Column(name = "requires_api_key", nullable = false)
    private boolean requiresApiKey;

    /** Execution order among enabled connectors. */
    @Column(nullable = false)
    private Integer priority;

    /** Connector-specific settings that do not deserve first-class columns yet. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configJson = new LinkedHashMap<>();

    /** Timestamp when the connector was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the connector was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
