package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "trunks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Trunk {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String sipUri;

    private String username;
    private String password;
    private String region;
    private boolean enabled;
}
