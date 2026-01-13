package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "dids", uniqueConstraints = @UniqueConstraint(columnNames = {"tenantId","number"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Did {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;


    @Column(nullable = false,name = "tenant_id", length = 36)
    private String tenantId;

    @Column(nullable = false)
    private String number;

    @Column(nullable = false)
    private String entrypoint; // team:support

    private String trunkId;
    private String status; // active|paused
}
