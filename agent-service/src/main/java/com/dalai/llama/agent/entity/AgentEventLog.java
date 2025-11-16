package com.dalai.llama.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "agent_event_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentEventLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "agent_session_id")
    private Long agentSessionId;

    @Column(name = "call_session_id")
    private Long callSessionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "event_category", length = 50)
    private String eventCategory;

    @Column(length = 20)
    @Builder.Default
    private String severity = "INFO";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
