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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_trends")
/**
 * Normalized and ranked trend record shown to users and used for idea/storyboard generation.
 */
public class CreatorTrend {

    /** Primary key for the normalized trend. */
    @Id
    private UUID id;

    /** Target publishing platform this trend is predicted for. */
    @Column(name = "platform_code", nullable = false, length = 64)
    private String platformCode;

    /** Primary category code for this trend. */
    @Column(name = "category_code", nullable = false, length = 64)
    private String categoryCode;

    /** Country or market context for this trend. */
    @Column(name = "country_code", nullable = false, length = 16)
    private String countryCode;

    /** Short user-facing trend title. */
    @Column(nullable = false, length = 240)
    private String title;

    /** Trend explanation shown in UI and used in prompts. */
    @Column(columnDefinition = "text")
    private String summary;

    /** Primary source signal for traceability. */
    @Column(name = "source_name", length = 96)
    private String sourceName;

    /** Optional source URL for traceability. */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    /** Overall ranking score after normalization/prediction. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal score;

    /** Momentum score indicating how quickly the signal is moving. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal velocity;

    /** JSON array of hashtags, phrases, or classifier tags. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    /** Normalized source details kept for analysis and prompt grounding. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sourcePayload = new LinkedHashMap<>();

    /** AI prediction details and reasoning for this trend. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prediction_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> predictionPayload = new LinkedHashMap<>();

    /** Trend lifecycle state such as ACTIVE, STALE, HIDDEN, or ARCHIVED. */
    @Column(nullable = false, length = 32)
    private String status;

    /** First time this trend was observed. */
    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    /** Most recent time this trend was observed. */
    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    /** Timestamp when the trend record was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (score == null) {
            score = BigDecimal.ZERO;
        }
        if (velocity == null) {
            velocity = BigDecimal.ZERO;
        }
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
