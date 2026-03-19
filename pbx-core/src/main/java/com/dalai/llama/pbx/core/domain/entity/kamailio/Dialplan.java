package com.dalai.llama.pbx.core.domain.entity.kamailio;


import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

/**
 * Kamailio dialplan table — number manipulation rules.
 * Used by Kamailio dp_translate() for number normalization.
 * Not to be confused with tenant_dialplan (FreeSWITCH XML).
 */
@Entity
@Table(name = "dialplan")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Dialplan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private Integer dpid;

    @Column(nullable = false)
    @Builder.Default
    private Integer pr = 0;

    @Column(name = "match_op", nullable = false)
    @Builder.Default
    private Integer matchOp = 1;

    @Column(name = "match_exp", nullable = false, length = 64)
    private String matchExp;

    @Column(name = "match_len", nullable = false)
    @Builder.Default
    private Integer matchLen = 0;

    @Column(name = "subst_exp", length = 64)
    @Builder.Default
    private String substExp = "";

    @Column(name = "repl_exp", nullable = false, length = 256)
    private String replExp;

    @Column(length = 512)
    @Builder.Default
    private String attrs = "";

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "product_code", length = 30)
    private String productCode;
}