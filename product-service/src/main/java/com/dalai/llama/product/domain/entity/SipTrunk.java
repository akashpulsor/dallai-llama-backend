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

    private String didwwTrunkId;
    private String didwwSipConfigId;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
