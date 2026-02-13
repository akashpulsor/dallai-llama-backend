package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.DataRegion;
import com.dalai.llama.tenant.domain.entity.enums.RedactionLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "compliance_policies",
        uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id"))
@Getter
@Setter
public class CompliancePolicy {

    @Id
    @UuidGenerator
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    // --- CONSENT & TRANSPARENCY ---
    private boolean consentPromptRequired = true;
    private boolean aiDisclosureRequired = true; // "This is an AI assistant..."

    // --- DATA RETENTION (Split for AI) ---
    private int audioRetentionDays = 90;      // Audio is heavy and high-risk
    private int transcriptRetentionDays = 365; // Transcripts are valuable for AI training/LLM context

    @Enumerated(EnumType.STRING)
    private DataRegion dataRegion = DataRegion.IN;

    // --- AI PRIVACY & REDACTION ---
    @Enumerated(EnumType.STRING)
    private RedactionLevel redactionLevel = RedactionLevel.PARTIAL;

    private boolean piiPauseRequired; // Physical recording pause
    private boolean anonymizeTranscript = true; // Replaces names with [USER] in LLM logs

    // --- OUTBOUND GUARDRAILS ---
    private boolean dncCheckRequired = true;
    private boolean callTimeRestrictionEnabled;

    private LocalTime callWindowStart = LocalTime.of(9, 0);
    private LocalTime callWindowEnd = LocalTime.of(21, 0);

    private String timeZoneId = "Asia/Kolkata"; // Crucial: Windows are meaningless without a TZ

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> blockedDays; // JSON array (e.g., ["SUNDAY"])

    // --- AUDIT & OPS ---
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}



