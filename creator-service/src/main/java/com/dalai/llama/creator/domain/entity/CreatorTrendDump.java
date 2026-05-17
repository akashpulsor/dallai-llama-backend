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
@Table(name = "creator_trend_dumps")
/**
 * Raw or semi-structured connector output stored per scheduler window before trend normalization.
 */
public class CreatorTrendDump {

    /** Primary key for the raw trend dump. */
    @Id
    private UUID id;

    /** Target platform context the dump was collected for. */
    @Column(name = "platform_code", nullable = false, length = 64)
    private String platformCode;

    /** Category context the dump was collected for. */
    @Column(name = "category_code", nullable = false, length = 64)
    private String categoryCode;

    /** Country or market context for the dump. */
    @Column(name = "country_code", nullable = false, length = 16)
    private String countryCode;

    /** Start timestamp of the collection window. */
    @Column(name = "window_started_at", nullable = false)
    private OffsetDateTime windowStartedAt;

    /** End timestamp of the collection window. */
    @Column(name = "window_ended_at", nullable = false)
    private OffsetDateTime windowEndedAt;

    /** Source that produced the dump, for example reddit or mock_trend_source. */
    @Column(name = "source_name", nullable = false, length = 96)
    private String sourceName;

    /** Optional URL used for collection or traceability. */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    /** Unnormalized or semi-structured source payload preserved for audit and later analysis. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawPayload = new LinkedHashMap<>();

    /** Collection status such as COLLECTED, NORMALIZED, FAILED, or IGNORED. */
    @Column(nullable = false, length = 32)
    private String status;

    /** Timestamp when the dump was stored. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
