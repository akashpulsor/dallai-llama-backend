package com.dalai.llama.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "queues")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Queue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "queue_id", unique = true, nullable = false)
    private String queueId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "queue_name", nullable = false)
    private String queueName;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "max_wait_time_seconds")
    @Builder.Default
    private Integer maxWaitTimeSeconds = 300;

    @Column(name = "max_queue_size")
    @Builder.Default
    private Integer maxQueueSize = 100;

    @Builder.Default
    private Integer priority = 5;

    @Column(name = "routing_strategy", length = 50)
    @Builder.Default
    private String routingStrategy = "LONGEST_IDLE";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_skills", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> requiredSkills = new ArrayList<>();

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
