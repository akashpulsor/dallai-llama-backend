package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.AppType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Defines which apps are available for a product.
 * When a tenant subscribes to a product, they get access to these apps.
 */
@Entity
@Table(name = "product_apps", uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_app_type", columnNames = {"product_id", "app_type"})
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductApp {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false)
    private AppType appType;

    @Column(nullable = false)
    private String subdomain;

    @Column(nullable = false)
    private String displayName;

    /**
     * Keycloak client suffix. NULL means use tenant's primary client.
     * e.g., "ivr" → dalaillama-{tenant-slug}-ivr
     */
    private String keycloakClientSuffix;

    @Column(nullable = false)
    private String frontendImage;

    @Builder.Default
    private int frontendPort = 80;

    private String requiredRoles;
    private String icon;
    private String description;

    @Builder.Default
    private int displayOrder = 0;

    @Builder.Default
    private boolean enabled = true;

    private Instant createdAt;
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