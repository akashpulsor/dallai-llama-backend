package com.dalai.llama.llmgateway.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tenant_model_override")
public class TenantModelOverride {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "tenantId", column = @Column(name = "tenant_id")),
            @AttributeOverride(name = "modelId", column = @Column(name = "model_id"))
    })
    private TenantModelOverrideId id;

    @Column(name = "rpm_override")
    private Integer rpmOverride;

    @Column(name = "tpm_override")
    private Integer tpmOverride;

    @Column(name = "max_concurrent_override")
    private Integer maxConcurrentOverride;

    @Column(nullable = false)
    private Boolean allowed;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
