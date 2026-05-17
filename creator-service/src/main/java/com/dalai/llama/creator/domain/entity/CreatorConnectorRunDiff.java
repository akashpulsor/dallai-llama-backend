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
@Table(name = "creator_connector_run_diffs")
/**
 * Per-run diff between connector output and already stored structured signals.
 */
public class CreatorConnectorRunDiff {

    /** Primary key for the connector run diff. */
    @Id
    private UUID id;

    /** Connector run that produced this diff. */
    @Column(name = "connector_run_id")
    private UUID connectorRunId;

    /** Connector code copied for querying and audit. */
    @Column(name = "connector_code", nullable = false, length = 96)
    private String connectorCode;

    /** Target publishing platform this diff belongs to. */
    @Column(name = "target_platform_code", nullable = false, length = 64)
    private String targetPlatformCode;

    /** Category this diff belongs to. */
    @Column(name = "category_code", nullable = false, length = 64)
    private String categoryCode;

    /** Country or market context. */
    @Column(name = "country_code", nullable = false, length = 16)
    private String countryCode;

    /** Total structured signals returned by the connector. */
    @Column(name = "incoming_count", nullable = false)
    private Integer incomingCount;

    /** Signals inserted for the first time. */
    @Column(name = "new_count", nullable = false)
    private Integer newCount;

    /** Signals skipped because their dedupe key already existed. */
    @Column(name = "duplicate_count", nullable = false)
    private Integer duplicateCount;

    /** Signals whose existing record changed. Reserved for future deep comparison. */
    @Column(name = "changed_count", nullable = false)
    private Integer changedCount;

    /** Previously seen signals missing from this run. Reserved for future decay logic. */
    @Column(name = "missing_count", nullable = false)
    private Integer missingCount;

    /** JSON detail including new and duplicate dedupe keys. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> diffPayload = new LinkedHashMap<>();

    /** Timestamp when this diff was stored. */
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
