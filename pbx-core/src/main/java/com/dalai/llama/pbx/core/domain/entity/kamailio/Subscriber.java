package com.dalai.llama.pbx.core.domain.entity.kamailio;



import com.dalai.llama.pbx.core.domain.enums.SubscriberType;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Kamailio subscriber table — SIP digest authentication.
 *
 * Write path: tenant-service → POST /api/v1/write/kamailio/subscribers
 *             AgentService   → auto-creates when agent is created
 * Read path:  Kamailio auth_db module (direct DB read)
 *             FreeSWITCH mod_xml_curl → /internal/freeswitch/directory
 *
 * ha1  = MD5(username:realm:password)     — used for standard SIP auth
 * ha1b = MD5(username@realm:realm:password) — used for auth with user@domain
 * Kamailio subscriber table — SIP digest authentication.
 *
 * Write path: tenant-service → POST /api/v1/write/kamailio/subscribers
 *             AgentService   → auto-creates when agent is created
 * Read path:  Kamailio auth_db module (direct DB read)
 *             FreeSWITCH mod_xml_curl → /internal/freeswitch/directory
 *
 * ha1  = MD5(username:realm:password)     — used for standard SIP auth
 * ha1b = MD5(username@realm:realm:password) — used for auth with user@domain
 *
 * namespace: tenant slug (e.g., "acme") — used by FreeSWITCH directory to set
 *            user_context = "tenant_{namespace}" matching dialplan context.
 */
@Entity
@Table(name = "subscriber")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 64)
    private String domain;

    @Column(length = 64)
    @Builder.Default
    private String password = "";

    @Column(nullable = false, length = 128)
    private String ha1;

    @Column(nullable = false, length = 128)
    private String ha1b;

    @Column(length = 255)
    private String rpid;

    // ── PBX-Core extensions (Kamailio ignores these columns) ──

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscriber_type", length = 20)
    @Builder.Default
    private SubscriberType subscriberType = SubscriberType.AGENT;

    @Column(name = "display_name", length = 100)
    private String displayName;

    /**
     * Tenant namespace/slug (e.g., "acme").
     * FreeSWITCH directory uses this to set user_context = "tenant_{namespace}"
     * which must match the dialplan context in tenant_dialplan table.
     */
    @Column(name = "namespace", length = 64)
    private String namespace;

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