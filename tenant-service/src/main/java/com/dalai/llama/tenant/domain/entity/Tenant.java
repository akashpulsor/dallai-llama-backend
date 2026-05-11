package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.*;

@Entity
@Table(name = "tenants")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String slug;

    private String companyName;
    private String gstin;

    @Column(nullable = false)
    private String primaryContactName;

    @Column(nullable = false)
    private String primaryContactEmail;

    private String primaryContactPhone;
    private String billingEmail;

    private String country;
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    private String substatus;

    @Column(name = "status_message", length = 2000)
    private String statusMessage;

    private OffsetDateTime statusChangedAt;

    // ─── Keycloak ──────────────────────────────────────────────────────
    private String keycloakRealmName;
    private String keycloakRealmId;

    /** Public Keycloak base URL (what the browser hits). */
    @Column(length = 255)
    private String keycloakUrl;

    /** Full OIDC issuer URL: {keycloakUrl}/realms/{keycloakRealmName} */
    @Column(length = 500)
    private String keycloakIssuer;



    @OneToMany(
            mappedBy = "tenant",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("displayOrder ASC")
    private List<AppPanel> appPanels = new ArrayList<>();

    /**
     * Add or update a panel by keycloakClientId. Existing panel fields are updated;
     * a new panel is added if none exists for the clientId.
     */
    public AppPanel upsertAppPanel(AppPanel incoming) {
        Optional<AppPanel> existing = appPanels.stream()
                .filter(p -> Objects.equals(p.getKeycloakClientId(), incoming.getKeycloakClientId()))
                .findFirst();

        if (existing.isPresent()) {
            AppPanel p = existing.get();
            p.setAppType(incoming.getAppType());
            p.setDisplayName(incoming.getDisplayName());
            p.setSubdomain(incoming.getSubdomain());
            p.setUrl(incoming.getUrl());
            p.setIcon(incoming.getIcon());
            p.setDisplayOrder(incoming.getDisplayOrder());
            p.setRequiredRoles(incoming.getRequiredRoles());
            if (incoming.getTenantApp() != null) {
                p.setTenantApp(incoming.getTenantApp());
            }
            return p;
        } else {
            incoming.setTenant(this);
            appPanels.add(incoming);
            return incoming;
        }
    }

    public void removeAppPanel(String keycloakClientId) {
        appPanels.removeIf(p -> Objects.equals(p.getKeycloakClientId(), keycloakClientId));
    }
    // ─── Admin user ────────────────────────────────────────────────────
    private String adminUserId;
    private String adminUserEmail;

    // ─── Billing ───────────────────────────────────────────────────────
    private UUID walletId;
    private String billingState;
    private OffsetDateTime billingReadyAt;

    // ─── Lifecycle ─────────────────────────────────────────────────────
    private OffsetDateTime createdAt;
    private OffsetDateTime activatedAt;
    private OffsetDateTime suspendedAt;
    private String suspensionReason;
    private OffsetDateTime deletedAt;
    private OffsetDateTime expiresAt;

    @Version
    private long version;

    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
        status = TenantStatus.CREATED;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public String getRealm() {
        return this.keycloakRealmName;
    }

    public String getDomain() {
        return slug + ".dalaillama.in";
    }


}