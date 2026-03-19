package com.dalai.llama.pbx.core.domain.entity.core;


import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Pre-generated FreeSWITCH dialplan + directory XML per tenant.
 *
 * Write path: tenant-service → POST /api/v1/write/tenant-dialplan
 * Read path:  FreeSWITCH mod_xml_curl → GET /internal/freeswitch/dialplan?context=tenant_{slug}
 *
 * context is unique — "tenant_{slug}" format. FreeSWITCH sets the context
 * from the X-Tenant-Context header that Kamailio adds to the INVITE.
 *
 * dialplan_xml: full FreeSWITCH dialplan XML (product-specific routing logic)
 * directory_xml: optional override for user directory (null = use subscriber table)
 */
@Entity
@Table(name = "tenant_dialplan")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantDialplan {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, unique = true, length = 100)
    private String context;

    @Column(name = "product_code", nullable = false, length = 30)
    private String productCode;

    @Column(name = "dialplan_xml", nullable = false, columnDefinition = "TEXT")
    private String dialplanXml;

    @Column(name = "directory_xml", columnDefinition = "TEXT")
    private String directoryXml;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}