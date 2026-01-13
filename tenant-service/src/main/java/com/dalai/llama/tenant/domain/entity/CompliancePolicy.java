package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.DataRegion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "compliance_policies",
        uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id"))
@Getter
@Setter
public class CompliancePolicy {

    @Id
    @UuidGenerator
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    private boolean consentPromptRequired = true;
    private int retentionDays = 90;

    @Enumerated(EnumType.STRING)
    private DataRegion dataRegion = DataRegion.IN;

    private boolean piiPauseRequired;
    private boolean dncCheckRequired = true;
    private boolean callTimeRestrictionEnabled;

    private LocalTime callWindowStart;
    private LocalTime callWindowEnd;

    private String blockedDays; // JSON array as string

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
