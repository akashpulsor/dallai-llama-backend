package com.dalai.llama.pbx.core.domain.entity.campaign;


import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-bot escalation intent threshold.
 *
 * Admin configures: "When caller shows intent X with confidence > Y → action Z"
 * voice-brain reads these at call start and checks rolling confidence after every LLM turn.
 *
 * Example: intent=interested, threshold=0.85, action=TRANSFER_QUEUE, target=sales
 *   → When LLM returns intent="interested" with rolling confidence > 0.85 → transfer to sales queue.
 */
@Entity
@Table(name = "bot_escalation_intents", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bot_escalation_intent", columnNames = {"bot_id", "intent_name"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BotEscalationIntent {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "intent_name", nullable = false, length = 100)
    private String intentName;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, precision = 4, scale = 3)
    @Builder.Default
    private BigDecimal threshold = new BigDecimal("0.900");

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String action = "TRANSFER_QUEUE";  // TRANSFER_QUEUE, TRANSFER_AGENT, TRANSFER_EXTERNAL, HANGUP

    @Column(length = 100)
    private String target;  // queue name, extension, or phone number

    @Builder.Default
    private Integer priority = 0;

    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}