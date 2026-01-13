package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "trunks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Trunk {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false,name = "tenant_id", length = 36)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    /** Example: sip:sip.mycarrier.com:5060 OR sip:username@sip.mycarrier.com */
    @Column(nullable = false)
    private String sipUri;

    /** Credentials for outbound authentication */
    private String username;
    private String password;

    /** Comma-separated E.164 prefixes: +1,+44,+91 */
    private String prefixes;

    /** Priority for route selection: lower = higher priority */
    @Column(nullable = false)
    private Integer priority;

    /** Weight used in dispatcher (load-balancing) */
    private Integer weight;

    /** Region (optional for selecting geographically closest trunk) */
    private String region;

    private boolean enabled;
}
