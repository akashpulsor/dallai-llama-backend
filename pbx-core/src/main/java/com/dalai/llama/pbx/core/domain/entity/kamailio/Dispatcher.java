package com.dalai.llama.pbx.core.domain.entity.kamailio;


import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

/**
 * Kamailio dispatcher table — FreeSWITCH destination sets.
 * setid=1 is the default FreeSWITCH group.
 * Kamailio ds_select_dst picks a destination from the set.
 */
@Entity
@Table(name = "dispatcher")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Dispatcher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    @Builder.Default
    private Integer setid = 0;

    @Column(nullable = false, length = 192)
    private String destination;

    @Builder.Default
    private Integer flags = 0;

    @Builder.Default
    private Integer priority = 0;

    @Column(length = 128)
    @Builder.Default
    private String attrs = "";

    @Column(length = 128)
    @Builder.Default
    private String description = "";

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;
}