package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
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
@Table(name = "creator_trend_signals")
/**
 * Structured, normalized source item produced by a connector run before global trend ranking.
 */
public class CreatorTrendSignal {

    /** Primary key for the trend signal. */
    @Id
    private UUID id;

    /** Connector run that produced this signal. */
    @Column(name = "connector_run_id")
    private UUID connectorRunId;

    /** Connector code copied for querying and audit. */
    @Column(name = "connector_code", nullable = false, length = 96)
    private String connectorCode;

    /** Signal source platform, for example reddit. */
    @Column(name = "source_platform_code", nullable = false, length = 64)
    private String sourcePlatformCode;

    /** Target publishing platform the signal will inform. */
    @Column(name = "target_platform_code", nullable = false, length = 64)
    private String targetPlatformCode;

    /** Category assigned to this signal. */
    @Column(name = "category_code", nullable = false, length = 64)
    private String categoryCode;

    /** Country or market context. */
    @Column(name = "country_code", nullable = false, length = 16)
    private String countryCode;

    /** Source type/call method used to create the signal. */
    @Column(name = "source_type", nullable = false, length = 80)
    private String sourceType;

    /** Source URL for this signal, when available. */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    /** Structured signal title. */
    @Column(nullable = false, length = 280)
    private String title;

    /** Structured signal summary. */
    @Column(columnDefinition = "text")
    private String summary;

    /** Main searchable/classifiable text for the signal. */
    @Column(name = "signal_text", columnDefinition = "text")
    private String signalText;

    /** Author/channel/community/source identity, when available. */
    @Column(name = "source_author", length = 180)
    private String sourceAuthor;

    /** Source-specific engagement metric normalized into a number. */
    @Column(name = "engagement_score", nullable = false, precision = 12, scale = 2)
    private BigDecimal engagementScore;

    /** Connector-side score before global trend ranking. */
    @Column(name = "rank_score", nullable = false, precision = 12, scale = 2)
    private BigDecimal rankScore;

    /** Original source publish timestamp, when available. */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** Timestamp when our scheduler observed this signal. */
    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    /** Per-item raw source payload. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_item", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawItem = new LinkedHashMap<>();

    /** Per-item normalized metadata used by ranking and analysis. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> normalizedPayload = new LinkedHashMap<>();

    /** Stable key used to avoid storing the same source signal repeatedly. */
    @Column(name = "dedupe_key", nullable = false, length = 240)
    private String dedupeKey;

    /** Timestamp when this structured signal was stored. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (observedAt == null) {
            observedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
