package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.AppType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductApp - UI applications for each product.
 *
 * This is the SOURCE OF TRUTH for:
 * - App type (CONTACT_CENTER, IVR_BUILDER, ADMIN_PANEL, etc.)
 * - Icon (emoji)
 * - Frontend image (Docker image)
 * - Required roles
 * - Subdomain
 * - Keycloak client suffix
 *
 * tenant-service reads this via internal API - NO hardcoded product config!
 */
@Entity
@Table(
        name = "product_apps",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_product_app_type",
                columnNames = {"product_id", "app_type"}
        ),
        indexes = @Index(name = "idx_product_apps_product_id", columnList = "product_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductApp {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 50)
    private AppType appType;

    @Column(name = "subdomain", nullable = false, length = 50)
    private String subdomain;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "keycloak_client_suffix", length = 50)
    private String keycloakClientSuffix;

    @Column(name = "frontend_image", nullable = false, length = 200)
    private String frontendImage;

    @Column(name = "frontend_port")
    @Builder.Default
    private Integer frontendPort = 80;

    @Column(name = "required_roles", length = 200)
    private String requiredRoles;

    @Column(name = "icon", length = 200)
    private String icon;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "enabled")
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

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