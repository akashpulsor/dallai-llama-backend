package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "signaling_configs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenantId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SignalingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    private String authRealm;

    @ElementCollection
    private List<String> dispatcherTargets;

    private String turnRealm;
    private String turnPolicy;
    private String turnPortRange;

    private String wssUrl;

    @ElementCollection
    private List<String> stunUrls;

    @ElementCollection
    private List<String> turnUrls;

    // Derived endpoints / FQDNs
    private String sipFqdn;
    private String pbxFqdn;
    private String turnFqdn;
    private String wssFqdn;

    // TLS Secrets / Certs
    private String sipTlsSecret;
    private String turnTlsSecret;
    private String wssTlsSecret;
    private Instant certIssuedAt;
    private Instant certExpiresAt;

    private Integer version;
    private Instant updatedAt;

    // 🧩 NEW SBC/Media fields
    /** Whether RTPengine was provisioned (media relay enabled). */
    private Boolean enableRtpEngine = true;

    /** Whether TURN (CoTURN) was provisioned for NAT traversal. */
    private Boolean enableTurn = true;

    /** Whether SBC logic (Kamailio as SBC) was used. */
    private Boolean sbcEnabled = true;

    /** Optional AI features summary (for analytics/debug). */
    private String aiFeaturesSummary;

    /** Optional health status (from orchestrator checks). */
    private String healthStatus;
}
