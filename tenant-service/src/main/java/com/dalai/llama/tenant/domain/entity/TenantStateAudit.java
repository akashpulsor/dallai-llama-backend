package com.dalai.llama.tenant.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_state_audit")
@Getter
@Setter
public class TenantStateAudit {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Tenant tenant;

    private String oldStatus;
    private String newStatus;
    private String oldSubstatus;
    private String newSubstatus;

    private String triggerType;
    private String triggerSource;
    private String triggerReference;

    private String message;

    @Column(columnDefinition = "jsonb")
    private String metadata;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
