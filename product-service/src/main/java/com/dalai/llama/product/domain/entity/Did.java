package com.dalai.llama.product.domain.entity;


import com.dalai.llama.product.domain.entity.enums.DidStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "dids",
        indexes = {
                @Index(name = "idx_did_tenant", columnList = "tenant_id"),
                @Index(name = "idx_did_status", columnList = "status")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Did {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = true)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sip_trunk_id")
    private SipTrunk sipTrunk;

    @Column(nullable = false, unique = true)
    private String number;

    private String displayNumber;

    @Column(nullable = false)
    private String country;

    private String region;
    private String city;

    private String didwwDidId;
    private String didwwTrunkGroupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DidStatus status;

    private BigDecimal monthlyRental;
    private String currency;

    private Instant provisionedAt;
    private Instant releasedAt;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    // ✅ Mirror DB constraint at application level
    @PrePersist
    @PreUpdate
    private void validateTenantConstraint() {
        if (status == null) {
            return; // let @Column(nullable = false) handle this
        }

        boolean isUnownedState =
                status == DidStatus.AVAILABLE ||
                        status == DidStatus.RELEASED;

        if (!isUnownedState && tenantId == null) {
            throw new IllegalStateException(
                    "tenantId must not be null for status " + status
            );
        }
    }
}
