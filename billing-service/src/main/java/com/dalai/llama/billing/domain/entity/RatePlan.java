package com.dalai.llama.billing.domain.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rate_plans")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatePlan {

    @Id
    private UUID id;

    @Column(unique = true, nullable = false)
    private String code;

    private String name;
    private String description;

    private boolean isDefault;
    private boolean active;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;
}
