package com.dalai.llama.pbx.core.domain.entity.core;


import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Thin DB-backed cache of TenantApp from tenant-service.
 *
 * Lookup order (TenantConfigCacheService):
 *   1. Redis GET "tenant:config:{tenantId}"   → HIT? return immediately
 *   2. DB SELECT from this table              → HIT + not expired? return + warm Redis
 *   3. HTTP GET tenant-service                → write here + Redis, return
 *
 * Indexed columns are the ones used in the call-auth hot path:
 *   - did_number: inbound call → resolve tenantId
 *   - sip_domain: FreeSWITCH directory lookup → resolve tenantId
 *   - max_channels, ai_enabled, recording_enabled: fast checks without parsing JSON
 *
 * config_snapshot JSONB holds the FULL TenantApp response so PBX-Core never
 * needs to enumerate 80+ columns — just caches whatever tenant-service returns.
 *
 * Cache warmth: tenant-service calls POST /api/v1/write/cache/warm after provisioning.
 * Cache eviction: TTL-based (5min default) + explicit via POST /api/v1/write/cache/evict/{tenantId}.
 */
@Entity
@Table(name = "tenant_config_cache")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantConfigCache {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "did_number", length = 20)
    private String didNumber;

    @Column(name = "sip_domain", nullable = false, length = 100)
    private String sipDomain;

    @Column(name = "product_code", nullable = false, length = 30)
    private String productCode;

    @Column(nullable = false, length = 100)
    private String namespace;

    // ── Hot-path fields for call authorization (avoid parsing JSON) ──

    @Column(name = "max_channels")
    @Builder.Default
    private Integer maxChannels = 30;

    @Column(name = "max_inbound")
    private Integer maxInbound;

    @Column(name = "max_outbound")
    private Integer maxOutbound;

    @Column(name = "ai_enabled")
    @Builder.Default
    private Boolean aiEnabled = false;

    @Column(name = "recording_enabled")
    @Builder.Default
    private Boolean recordingEnabled = false;

    @Column(name = "deployment_model", length = 20)
    @Builder.Default
    private String deploymentModel = "SHARED";

    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    // ── Full TenantApp snapshot ──

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> configSnapshot = Map.of();

    @Column(name = "cached_at")
    private Instant cachedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    void prePersist() { cachedAt = Instant.now(); }
}