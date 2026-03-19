package com.dalai.llama.pbx.core.domain.entity.core;


import com.dalai.llama.pbx.core.domain.enums.TrunkAuthType;
import com.dalai.llama.pbx.core.domain.enums.TrunkTransport;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * SIP trunk configuration for outbound calling.
 *
 * Creating a trunk via /api/v1/trunks also INSERTs a uacreg row
 * so Kamailio can authenticate outbound INVITEs to the trunk provider.
 * This sync is handled by TrunkService.
 */
@Entity
@Table(name = "sip_trunks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SipTrunk {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String provider;

    @Column(name = "sip_server", nullable = false, length = 200)
    private String sipServer;

    @Column(name = "sip_port")
    @Builder.Default
    private Integer sipPort = 5060;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    @Builder.Default
    private TrunkTransport transport = TrunkTransport.UDP;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", length = 20)
    @Builder.Default
    private TrunkAuthType authType = TrunkAuthType.DIGEST;

    @Column(name = "auth_username", length = 100)
    private String authUsername;

    @Column(name = "auth_password_encrypted", columnDefinition = "TEXT")
    private String authPasswordEncrypted;

    @Column(name = "max_concurrent_outbound")
    @Builder.Default
    private Integer maxConcurrentOutbound = 10;

    @Column(name = "outbound_caller_id", length = 20)
    private String outboundCallerId;

    @Column(name = "codec_preference", length = 200)
    private String codecPreference;

    @Column(name = "dispatcher_set_id")
    private Integer dispatcherSetId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}