package com.dalai.llama.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "session_id", unique = true, nullable = false)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "login_time", nullable = false)
    @Builder.Default
    private OffsetDateTime loginTime = OffsetDateTime.now();

    @Column(name = "logout_time")
    private OffsetDateTime logoutTime;

    @Column(name = "expected_logout_time")
    private OffsetDateTime expectedLogoutTime;

    @Column(name = "session_duration_seconds")
    private Integer sessionDurationSeconds;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "websocket_session_id")
    private String websocketSessionId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "disconnect_reason")
    private String disconnectReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public void endSession(String reason) {
        this.logoutTime = OffsetDateTime.now();
        this.isActive = false;
        this.disconnectReason = reason;
        this.sessionDurationSeconds = (int) java.time.Duration.between(loginTime, logoutTime).getSeconds();
    }
}
