package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.TransportProtocol;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_sip_trunks",
        indexes = {
                @Index(name = "idx_tenant_trunk_tenant", columnList = "tenant_id"),
                @Index(name = "idx_tenant_trunk_username", columnList = "username", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSipTrunk {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_plain")
    private String passwordPlain;

    @Column(nullable = false)
    @Builder.Default
    private String realm = "dalaillama.in";

    @Column(nullable = false)
    @Builder.Default
    private String domain = "sip.dalaillama.in";

    @Builder.Default
    private int port = 5060;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TransportProtocol transport = TransportProtocol.UDP;

    @Column(name = "max_concurrent_calls")
    @Builder.Default
    private int maxConcurrentCalls = 10;

    @Builder.Default
    private boolean active = true;

    @Column(name = "synced_to_kamailio")
    @Builder.Default
    private boolean syncedToKamailio = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}