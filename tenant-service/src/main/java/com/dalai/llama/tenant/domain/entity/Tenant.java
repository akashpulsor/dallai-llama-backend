package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

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

    private String country;
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    private String substatus;
    private String statusMessage;
    private OffsetDateTime statusChangedAt;

    @Enumerated(EnumType.STRING)
    private DeploymentModel deploymentModel;

    private String namespace;

    // Keycloak
    private String keycloakRealmName;
    private String keycloakRealmId;
    private String keycloakClientId;
    private String adminUserId;
    private String adminUserEmail;

    // Product
    private UUID planId;
    private String planCode;
    private OffsetDateTime planAssignedAt;

    // Billing
    private UUID walletId;
    private String billingState;
    private OffsetDateTime billingReadyAt;

    // Infra
    private String kafkaBootstrap;
    private String redisUrl;
    private String postgresUrl;
    private String mysqlUrl;

    // SIP
    private String sipExternalIp;
    private String sipUdpUrl;
    private String sipTlsUrl;
    private String turnUrl;
    private String websocketUrl;
    private String rtpengineSock;

    // DIDWW
    private String didwwTrunkId;
    private String didwwSipConfigId;

    private String dashboardUrl;

    // Lifecycle
    private OffsetDateTime createdAt;
    private OffsetDateTime activatedAt;
    private OffsetDateTime suspendedAt;
    private String suspensionReason;
    private OffsetDateTime deletedAt;

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
}
