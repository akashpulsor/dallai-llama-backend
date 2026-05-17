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
@Table(name = "creator_generation_jobs")
public class CreatorGenerationJob {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "job_type", nullable = false, length = 64)
    private String jobType;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Integer progress;

    @Column(name = "redis_key", length = 240)
    private String redisKey;

    @Column(name = "kafka_topic", length = 160)
    private String kafkaTopic;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputPayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> outputPayload = new LinkedHashMap<>();

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null || status.isBlank()) {
            status = "PENDING";
        }
        if (progress == null) {
            progress = 0;
        }
        if (inputPayload == null) {
            inputPayload = new LinkedHashMap<>();
        }
        if (outputPayload == null) {
            outputPayload = new LinkedHashMap<>();
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
