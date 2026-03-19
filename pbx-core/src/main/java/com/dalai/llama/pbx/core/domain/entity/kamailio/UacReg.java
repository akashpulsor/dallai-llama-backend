package com.dalai.llama.pbx.core.domain.entity.kamailio;


import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

/**
 * Kamailio uacreg table — outbound trunk registrations.
 *
 * Write path: TrunkService auto-syncs when SipTrunk is created/updated.
 * Read path:  Kamailio uac module for outbound INVITE authentication.
 *
 * tenant_id + trunk_id are PBX-Core extensions linking back to sip_trunks table.
 */
@Entity
@Table(name = "uacreg")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UacReg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "l_uuid", nullable = false, unique = true, length = 64)
    @Builder.Default
    private String lUuid = "";

    @Column(name = "l_username", nullable = false, length = 64)
    @Builder.Default
    private String lUsername = "";

    @Column(name = "l_domain", nullable = false, length = 64)
    @Builder.Default
    private String lDomain = "";

    @Column(name = "r_username", nullable = false, length = 64)
    @Builder.Default
    private String rUsername = "";

    @Column(name = "r_domain", nullable = false, length = 128)
    @Builder.Default
    private String rDomain = "";

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String realm = "";

    @Column(name = "auth_username", nullable = false, length = 64)
    @Builder.Default
    private String authUsername = "";

    @Column(name = "auth_password", nullable = false, length = 64)
    @Builder.Default
    private String authPassword = "";

    @Column(name = "auth_ha1", nullable = false, length = 128)
    @Builder.Default
    private String authHa1 = "";

    @Column(name = "auth_proxy", nullable = false, length = 128)
    @Builder.Default
    private String authProxy = "";

    @Column(nullable = false)
    @Builder.Default
    private Integer expires = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer flags = 0;

    @Column(name = "reg_delay", nullable = false)
    @Builder.Default
    private Integer regDelay = 0;

    @Column(name = "contact_addr", nullable = false, length = 255)
    @Builder.Default
    private String contactAddr = "";

    @Column(nullable = false, length = 128)
    @Builder.Default
    private String socket = "";

    // ── PBX-Core extensions ──

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "trunk_id")
    private UUID trunkId;
}