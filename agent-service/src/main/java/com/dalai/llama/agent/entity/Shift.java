package com.dalai.llama.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agent_shifts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shift {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "scheduled_start_at", nullable = false)
    private OffsetDateTime scheduledStartAt;

    @Column(name = "scheduled_end_at", nullable = false)
    private OffsetDateTime scheduledEndAt;

    @Column(name = "actual_start_at")
    private OffsetDateTime actualStartAt;

    @Column(name = "actual_end_at")
    private OffsetDateTime actualEndAt;

    @Column(name = "shift_type", length = 50)
    @Builder.Default
    private String shiftType = "REGULAR";

    @Column(length = 50)
    @Builder.Default
    private String status = "SCHEDULED";

    @Column(length = 64)
    @Builder.Default
    private String timezone = "UTC";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<Object> breaks = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
