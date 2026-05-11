package com.dalai.llama.tenant.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "tenant_app_panels",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_tenant_client_id",
                columnNames = {"tenant_id", "keycloak_client_id"}
        )
)
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppPanel {

    @Id
    @UuidGenerator
    private UUID id;

    /** Back-reference. Marked @JsonIgnore to prevent infinite loops on serialization. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /** Optional link to the specific TenantApp that contributed this panel. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_app_id")
    private TenantApp tenantApp;

    @Column(nullable = false, length = 50)
    private String appType;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 50)
    private String subdomain;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(length = 20)
    private String icon;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "keycloak_client_id", nullable = false, length = 100)
    private String keycloakClientId;

    @Column(name = "required_roles", nullable = false, length = 200)
    private String requiredRoles;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}