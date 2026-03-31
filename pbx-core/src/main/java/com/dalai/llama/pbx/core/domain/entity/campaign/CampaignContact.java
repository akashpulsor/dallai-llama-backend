package com.dalai.llama.pbx.core.domain.entity.campaign;


import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "campaign_contacts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CampaignContact {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    // ── Contact info ──
    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(length = 100)
    private String name;

    @Column(length = 100)
    private String email;

    @Column(length = 100)
    private String company;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_data", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> customData = Map.of();

    // ── Dialing state ──
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private ContactStatus status = ContactStatus.PENDING;

    @Column(name = "attempt_count")
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ── Call result ──
    @Column(name = "call_id", length = 100)
    private String callId;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(length = 50)
    private String disposition;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // ── Priority ──
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "hangup_cause", length = 50)
    private String hangupCause;

    @Column(name = "crm_id", length = 100)
    private String crmId;  // Salesforce Lead ID, HubSpot contact ID, etc.

    @Column(name = "crm_provider", length = 30)
    private String crmProvider;  // SALESFORCE, HUBSPOT, etc.

    @Column(name = "source", length = 20)
    @Builder.Default
    private String source = "CSV";  // CSV, CRM, API, MANUAL

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}