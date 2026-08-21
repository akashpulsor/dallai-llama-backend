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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @Column(name = "audit_id")
    private UUID auditId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(precision = 18, scale = 10)
    private BigDecimal cost;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(nullable = false, length = 32)
    private String status;

    private String error;

    @Column(name = "payload_uri")
    private String payloadUri;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
