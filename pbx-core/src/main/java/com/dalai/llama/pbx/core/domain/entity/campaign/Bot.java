package com.dalai.llama.pbx.core.domain.entity.campaign;




import com.dalai.llama.pbx.core.domain.enums.BotStatus;
import com.dalai.llama.pbx.core.domain.enums.TransferType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "bots",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "name"})
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Bot {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private BotStatus status = BotStatus.DRAFT;

    // ── LLM ──
    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "greeting_message", columnDefinition = "TEXT")
    private String greetingMessage;

    @Column(name = "goodbye_message", columnDefinition = "TEXT")
    private String goodbyeMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> guidelines = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_intents", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> allowedIntents = List.of();

    @Column(name = "fallback_message", columnDefinition = "TEXT")
    private String fallbackMessage;

    // ── Escalation ──
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "escalation_rules", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> escalationRules = Map.of();

    @Column(name = "transfer_target", length = 200)
    private String transferTarget;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", length = 20)
    @Builder.Default
    private TransferType transferType = TransferType.QUEUE;

    // ── Voice ──
    @Column(name = "voice_provider", length = 30)
    @Builder.Default
    private String voiceProvider = "piper";

    @Column(name = "voice_id", length = 100)
    private String voiceId;

    @Column(name = "voice_speed", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal voiceSpeed = BigDecimal.ONE;

    @Column(length = 10)
    @Builder.Default
    private String language = "en";

    // ── Behavior ──
    @Column(name = "max_turns")
    @Builder.Default
    private Integer maxTurns = 30;

    @Column(name = "max_duration_seconds")
    @Builder.Default
    private Integer maxDurationSeconds = 600;

    @Column(name = "dtmf_enabled")
    @Builder.Default
    private Boolean dtmfEnabled = true;

    @Column(name = "barge_in_enabled")
    @Builder.Default
    private Boolean bargeInEnabled = true;

    @Column(name = "sentiment_tracking")
    @Builder.Default
    private Boolean sentimentTracking = false;

    @Column(name = "transcript_enabled")
    @Builder.Default
    private Boolean transcriptEnabled = true;

    // ── RAG ──
    @Column(name = "knowledge_base_id")
    private UUID knowledgeBaseId;

    @Column(name = "rag_enabled")
    @Builder.Default
    private Boolean ragEnabled = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_data", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> customData = Map.of();

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}