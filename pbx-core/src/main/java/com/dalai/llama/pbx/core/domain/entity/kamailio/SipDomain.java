package com.dalai.llama.pbx.core.domain.entity.kamailio;


import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Kamailio domain table — SIP domains.
 * Each tenant gets a domain like "tenant-acme.dalaillama.in".
 * Kamailio domain module reads this to validate Request-URI domain.
 */
@Entity
@Table(name = "domain")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SipDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 64)
    private String domain;

    @Column(length = 64)
    private String did;

    @Column(name = "last_modified")
    private Instant lastModified;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @PrePersist
    void prePersist() { lastModified = Instant.now(); }

    @PreUpdate
    void preUpdate() { lastModified = Instant.now(); }
}