package com.dalai.llama.agent.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@Table(name = "agents", indexes = {
    @Index(name = "idx_agents_tenant", columnList = "tenant_id"),
    @Index(name = "idx_agents_external_id", columnList = "external_id"),
    @Index(name = "idx_agents_status", columnList = "tenant_id,status"),
    @Index(name = "idx_agents_availability", columnList = "tenant_id,availability_status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", unique = true, nullable = false)
    private String externalId; // Keycloak user ID

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String extension;

    @Column(name = "display_name")
    private String displayName;

    private String email;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    // Status Management
    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AgentStatus status = AgentStatus.OFFLINE;

    @Column(name = "availability_status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AvailabilityStatus availabilityStatus = AvailabilityStatus.AVAILABLE;

    @Builder.Default
    private Boolean online = false;

    @Builder.Default
    private Boolean available = true;

    // Configuration
    @Column(name = "max_concurrent_calls")
    @Builder.Default
    private Integer maxConcurrentCalls = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> skills = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "queue_memberships", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> queueMemberships = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> preferences = new HashMap<>();

    // Metrics
    @Column(name = "total_calls_handled")
    @Builder.Default
    private Integer totalCallsHandled = 0;

    @Column(name = "total_call_duration_seconds")
    @Builder.Default
    private Long totalCallDurationSeconds = 0L;

    @Column(name = "average_handle_time_seconds")
    @Builder.Default
    private Integer averageHandleTimeSeconds = 0;

    @Column(name = "last_call_at")
    private OffsetDateTime lastCallAt;

    // Timestamps
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // Business logic methods
    public boolean isAvailableForCall() {
        return online && available && 
               status == AgentStatus.ONLINE && 
               availabilityStatus == AvailabilityStatus.AVAILABLE;
    }

    public void incrementCallStats(long callDurationSeconds) {
        this.totalCallsHandled++;
        this.totalCallDurationSeconds += callDurationSeconds;
        this.averageHandleTimeSeconds = (int) (this.totalCallDurationSeconds / this.totalCallsHandled);
        this.lastCallAt = OffsetDateTime.now();
    }

    public void updateLastSeen() {
        this.lastSeenAt = OffsetDateTime.now();
    }

    // Enums
    public enum AgentStatus {
        OFFLINE,
        ONLINE,
        BUSY,
        BREAK,
        TRAINING,
        MEETING,
        LUNCH
    }

    public enum AvailabilityStatus {
        AVAILABLE,
        UNAVAILABLE,
        IN_CALL,
        AFTER_CALL_WORK,
        WRAP_UP
    }
}
