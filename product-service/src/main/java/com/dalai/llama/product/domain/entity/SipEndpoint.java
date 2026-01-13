package com.dalai.llama.product.domain.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "sip_endpoints",
        indexes = {
                @Index(
                        name = "idx_endpoint_username",
                        columnList = "username, domain",
                        unique = true
                ),
                @Index(
                        name = "idx_endpoint_did",
                        columnList = "did_id"
                ),
                @Index(
                        name = "idx_endpoint_pending_sync",
                        columnList = "registered_in_kamailio"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SipEndpoint {

    @Id
    @Column(nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "did_id", nullable = false)
    private Did did;

    /**
     * Example: did_919876543210
     */
    @Column(nullable = false)
    private String username;

    /**
     * HA1 hash: MD5(username:realm:password)
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private String realm;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "registered_in_kamailio", nullable = false)
    private boolean registeredInKamailio;

    @Column(name = "kamailio_synced_at")
    private Instant kamailioSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
