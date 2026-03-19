package com.dalai.llama.pbx.core.domain.entity.core;


import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * DB-backed TURN credentials cache.
 *
 * Primary store is Redis (Agent UI hits GET /api/v1/turn/credentials/{tenantSlug}
 * which reads from TurnCredentialsRedisService). This table serves as:
 *   - Audit trail of issued credentials
 *   - Redis rebuild source if Redis is flushed
 *   - Cleanup target for ScheduledTasks (delete expired rows)
 *
 * Credentials are HMAC-SHA1 ephemeral:
 *   username = "{expiry_timestamp}:tenant_{slug}"
 *   password = Base64(HMAC-SHA1(username, coturn_shared_secret))
 *
 * TTL is typically 86400s (24h) for provisioning, 3600s (1h) for runtime.
 */
@Entity
@Table(name = "turn_credentials_cache")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TurnCredentialsCache {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String username;

    @Column(nullable = false, length = 200)
    private String password;

    @Column(name = "turn_url", length = 200)
    private String turnUrl;

    @Column(name = "turns_url", length = 200)
    private String turnsUrl;

    @Column(name = "stun_url", length = 200)
    private String stunUrl;

    @Builder.Default
    private Integer ttl = 3600;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }
}