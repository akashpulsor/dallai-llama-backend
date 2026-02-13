package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_apps", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tenant_app_type", columnNames = {"tenant_id", "app_type"}),
        @UniqueConstraint(name = "uk_tenant_subdomain", columnNames = {"tenant_id", "subdomain"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantApp {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 50)
    private AppType appType;

    @Enumerated(EnumType.STRING)
    private DeploymentModel deploymentModel;

    @Column(nullable = false, length = 50)
    private String subdomain;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 100)
    private String keycloakClientId;

    @Column(nullable = false, length = 100)
    private String frontendService;

    @Column(nullable = false, length = 200)
    private String frontendImage;

    @Builder.Default
    @Column(nullable = false)
    private Integer frontendPort = 80;

    // Plan & Product (Moved from Tenant)
    private UUID planId;
    private String planCode;
    private OffsetDateTime planAssignedAt;
    private String productCode;

    // Infrastructure (Moved from Tenant)
    private String kafkaBootstrap;
    private String redisUrl;
    private String postgresUrl;
    private String mysqlUrl;

    // SIP & Telephony (Moved from Tenant)
    private String sipExternalIp;
    private String sipUdpUrl;
    private String sipTlsUrl;
    private String turnUrl;
    private String websocketUrl;
    private String rtpengineSock;

    // FreeSWITCH ESL (Moved from Tenant)
    private String freeswitchEslHost;
    private Integer freeswitchEslPort;
    private String freeswitchEslPassword;

    // DIDWW (Moved from Tenant)
    private String didwwTrunkId;
    private String didwwSipConfigId;

    private String dashboardUrl;

    private String namespace;
    // AI Features (Moved from Tenant)
    private boolean aiTranscriptionEnabled;
    private boolean aiRoutingEnabled;
    private boolean aiNoiseCancellationEnabled;

    // CC Builder Config (Moved from Tenant)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cc_builder_config")
    private String ccBuilderConfig;

    // UI & Meta
    @Column(length = 200)
    private String requiredRoles;

    @Column(length = 500)
    private String description;

    @Column(length = 50)
    private String icon;

    @Builder.Default
    @Column(nullable = false)
    private Integer displayOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private ProvisioningTaskStatus deploymentStatus = ProvisioningTaskStatus.PENDING;

    private Instant deployedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Helper Methods
    public boolean isDedicated() {
        return deploymentModel == DeploymentModel.DEDICATED;
    }

    public boolean hasAiEnabled() {
        return aiTranscriptionEnabled || aiRoutingEnabled || aiNoiseCancellationEnabled;
    }

    public String getFullDomain(String tenantSlug) {
        return subdomain + "." + tenantSlug + ".dalaillama.in";
    }

    public String getFullUrl(String tenantSlug) {
        return "https://" + getFullDomain(tenantSlug);
    }
}