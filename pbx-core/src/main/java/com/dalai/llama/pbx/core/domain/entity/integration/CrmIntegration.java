package com.dalai.llama.pbx.core.domain.entity.integration;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "crm_integrations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CrmIntegration {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 30)
    private String provider;  // SALESFORCE, HUBSPOT, ZOHO, FRESHSALES, PIPEDRIVE, CUSTOM

    @Column(length = 100)
    private String name;  // "Production Salesforce"

    @Column(name = "api_url", length = 500)
    private String apiUrl;

    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(name = "client_id", length = 500)
    private String clientId;

    @Column(name = "client_secret", length = 500)
    private String clientSecret;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_mapping", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, String> fieldMapping = Map.of();

    @Column(name = "sync_contacts")
    @Builder.Default
    private Boolean syncContacts = true;

    @Column(name = "sync_calls")
    @Builder.Default
    private Boolean syncCalls = true;

    @Column(name = "sync_notes")
    @Builder.Default
    private Boolean syncNotes = true;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = false;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}