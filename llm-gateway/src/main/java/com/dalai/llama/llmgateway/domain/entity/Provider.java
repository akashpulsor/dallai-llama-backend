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

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "provider")
public class Provider {

    @Id
    @Column(name = "provider_id", length = 64)
    private String providerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "adapter_class", nullable = false)
    private String adapterClass;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "account_rpm")
    private Integer accountRpm;

    @Column(name = "account_tpm")
    private Integer accountTpm;

    @Column(name = "max_concurrent")
    private Integer maxConcurrent;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
