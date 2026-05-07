package com.dalai.llama.product.domain.entity;


import com.dalai.llama.product.domain.entity.enums.SipProvider;
import com.dalai.llama.product.domain.entity.enums.SipTrunkStatus;
import com.dalai.llama.product.domain.entity.enums.TransportProtocol;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "sip_trunks")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SipTrunk {

    @Id
    private UUID id;

    private UUID tenantId; // NULL = platform trunk

    private String name;

    @Enumerated(EnumType.STRING)
    private SipProvider provider;

    @Column(name = "tech_prefix")
    private String techPrefix;

    private String server;
    private int port;

    @Enumerated(EnumType.STRING)
    private TransportProtocol transport;

    private String authType; // IP / CREDENTIAL / BOTH
    private String authUsername;
    private String authPasswordEncrypted;
    private String allowedIps;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> codecs;

    private int maxConcurrentCalls;
    private int maxCallsPerSecond;

    @Enumerated(EnumType.STRING)
    private SipTrunkStatus status;

    private Instant lastHealthCheck;
    private boolean isHealthy;

    /**
     * ISO 3166-1 alpha-2 country code (e.g. "IN", "US", "GB").
     * Required for platform trunks (tenantId = NULL) so the saga can route DIDs
     * to the correct carrier per country. Used by
     * {@code SipTrunkRepository.findBestPlatformTrunkForCountry}.
     */
    @Column(length = 2)
    private String country;

    /**
     * Selection priority when multiple platform trunks match a country.
     * LOWER value = HIGHER priority (sorted ASC). Default 100 — set lower
     * (e.g. 10) for preferred carriers, higher (e.g. 200) for fallback.
     */
    @Column(nullable = false)
    @Builder.Default
    private int priority = 100;

    /**
     * Provider-specific trunk identifier. Despite the historical column name
     * ({@code didww_trunk_id}), this is used for any provider — DIDWW, Epsilon,
     * Twilio, etc. Field name kept for backwards compatibility with existing data.
     * Cleanup tracked as deferred work.
     */
    private String didwwTrunkId;

    /**
     * Provider-specific SIP config identifier. Same provider-agnostic notes as
     * {@link #didwwTrunkId}.
     */
    private String didwwSipConfigId;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}