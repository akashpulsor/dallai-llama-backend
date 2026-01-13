package com.dalai.llama.product.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "plan_assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanAssignment {

    @Id
    private UUID id;

    private UUID tenantId;

    @ManyToOne
    @JoinColumn(name = "plan_id")
    private Plan plan;

    private Instant effectiveFrom;
    private Instant effectiveTo;

    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> entitlementOverrides;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
