package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.DeliveryStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_credential_deliveries", indexes = {
        @Index(name = "ix_cred_del_tenant_status", columnList = "tenant_id, status"),
        @Index(name = "ix_cred_del_kc_status", columnList = "keycloak_user_id, status"),
        @Index(name = "ix_cred_del_expires", columnList = "expires_at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class UserCredentialDelivery {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "tenant_user_id", nullable = false)
    private UUID tenantUserId;

    @Column(name = "keycloak_user_id", nullable = false, length = 100)
    private String keycloakUserId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "temp_password_enc", length = 500)
    private String tempPasswordEnc;

    @Column(name = "login_url", nullable = false, length = 300)
    private String loginUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "viewed_by", length = 100)
    private String viewedBy;

    @Column(name = "viewed_at")
    private OffsetDateTime viewedAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
