package com.dalai.llama.llmgateway.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "model_master")
public class ModelMaster {

    @Id
    @Column(name = "model_id", length = 128)
    private String modelId;

    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    @Column(nullable = false, length = 32)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String capabilities;

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "supports_streaming", nullable = false)
    private Boolean supportsStreaming;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "default_rpm", nullable = false)
    private Integer defaultRpm;

    @Column(name = "default_tpm", nullable = false)
    private Integer defaultTpm;

    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
