package com.dalai.llama.pbx.core.domain.entity.campaign;


import com.dalai.llama.pbx.core.domain.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "campaigns")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Campaign {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false, length = 20)
    private CampaignType campaignType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    // ── Bot ──
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bot_id")
    private Bot bot;

    // ── DID / Caller ID ──
    @Column(name = "did_number", length = 20)
    private String didNumber;

    @Column(name = "outbound_caller_id", length = 20)
    private String outboundCallerId;

    // ── Outbound Dialer ──
    @Enumerated(EnumType.STRING)
    @Column(name = "dialer_mode", length = 20)
    private DialerMode dialerMode;

    @Column(name = "pacing_ratio", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal pacingRatio = BigDecimal.ONE;

    @Column(name = "max_concurrent_calls")
    @Builder.Default
    private Integer maxConcurrentCalls = 5;

    @Column(name = "max_attempts_per_contact")
    @Builder.Default
    private Integer maxAttemptsPerContact = 3;

    @Column(name = "retry_delay_minutes")
    @Builder.Default
    private Integer retryDelayMinutes = 60;

    @Column(name = "amd_enabled")
    @Builder.Default
    private Boolean amdEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "amd_action", length = 20)
    @Builder.Default
    private AmdAction amdAction = AmdAction.HANGUP;

    // ── Inbound ──
    @Column(name = "queue_id")
    private UUID queueId;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_hours_action", length = 20)
    @Builder.Default
    private AfterHoursAction afterHoursAction = AfterHoursAction.VOICEMAIL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "after_hours_bot_id")
    private Bot afterHoursBot;

    // ── Schedule ──
    @Column(length = 50)
    @Builder.Default
    private String timezone = "Asia/Kolkata";

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    // ── Limits ──
    @Column(name = "max_daily_calls")
    private Integer maxDailyCalls;

    @Column(name = "max_total_calls")
    private Integer maxTotalCalls;

    // ── Stats (denormalized for fast reads) ──
    @Column(name = "total_contacts")
    @Builder.Default
    private Integer totalContacts = 0;

    @Column(name = "contacts_dialed")
    @Builder.Default
    private Integer contactsDialed = 0;

    @Column(name = "contacts_connected")
    @Builder.Default
    private Integer contactsConnected = 0;

    @Column(name = "contacts_completed")
    @Builder.Default
    private Integer contactsCompleted = 0;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}