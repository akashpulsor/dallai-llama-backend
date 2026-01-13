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
                @Index(name = "idx_did_tenant", columnList = "tenantId"),
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

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sip_trunk_id")
    private SipTrunk sipTrunk;

    @Column(nullable = false, unique = true)
    private String number;              // E.164

    private String displayNumber;

    private String country;
    private String region;
    private String city;

    private String didwwDidId;
    private String didwwTrunkGroupId;

    @Enumerated(EnumType.STRING)
    private DidStatus status;

    private BigDecimal monthlyRental;
    private String currency;

    private Instant provisionedAt;
    private Instant releasedAt;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
