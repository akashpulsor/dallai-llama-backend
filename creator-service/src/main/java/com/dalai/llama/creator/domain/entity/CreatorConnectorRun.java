package com.dalai.llama.creator.domain.entity;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import com.dalai.llama.creator.domain.ConnectorRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "creator_connector_runs")
/**
 * Audit row for one scheduler attempt to run one connector for one target platform/category/window.
 */
public class CreatorConnectorRun {

    /** Primary key for the connector run. */
    @Id
    private UUID id;

    /** Connector row used for this run. */
    @Column(name = "connector_id")
    private UUID connectorId;

    /** Connector code copied for audit stability. */
    @Column(name = "connector_code", nullable = false, length = 96)
    private String connectorCode;

    /** Target publishing platform this run is collecting signals for. */
    @Column(name = "target_platform_code", nullable = false, length = 64)
    private String targetPlatformCode;

    /** Signal source platform used by this connector. */
    @Column(name = "source_platform_code", nullable = false, length = 64)
    private String sourcePlatformCode;

    /** Category this connector run is collecting for. */
    @Column(name = "category_code", nullable = false, length = 64)
    private String categoryCode;

    /** Country or market context. */
    @Column(name = "country_code", nullable = false, length = 16)
    private String countryCode;

    /** Start of scheduler collection window. */
    @Column(name = "window_started_at", nullable = false)
    private OffsetDateTime windowStartedAt;

    /** End of scheduler collection window. */
    @Column(name = "window_ended_at", nullable = false)
    private OffsetDateTime windowEndedAt;

    /** Call method used for this run. */
    @Enumerated(EnumType.STRING)
    @Column(name = "call_method", nullable = false, length = 64)
    private ConnectorCallMethod callMethod;

    /** Run status such as PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, or RATE_LIMITED. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConnectorRunStatus status;

    /** Number of attempts made including retries. */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    /** Number of upstream requests made. */
    @Column(name = "request_count", nullable = false)
    private Integer requestCount;

    /** Number of structured signal items found. */
    @Column(name = "items_found", nullable = false)
    private Integer itemsFound;

    /** Last HTTP status, when applicable. */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** Machine-readable failure code. */
    @Column(name = "error_code", length = 120)
    private String errorCode;

    /** Human-readable failure detail. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /** Timestamp when the connector run started. */
    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    /** Timestamp when the connector run completed or failed. */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** Rendered request details used for this run. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestSnapshot = new LinkedHashMap<>();

    /** Small response summary or parse metadata. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> responseSnapshot = new LinkedHashMap<>();

    /** Structured connector output summary stored for debugging and replay. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> structuredPayload = new LinkedHashMap<>();

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (startedAt == null) {
            startedAt = OffsetDateTime.now();
        }
    }
}
