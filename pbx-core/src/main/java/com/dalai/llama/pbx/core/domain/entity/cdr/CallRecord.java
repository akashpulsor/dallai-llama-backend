package com.dalai.llama.pbx.core.domain.entity.cdr;


import com.dalai.llama.pbx.core.domain.enums.CallDirection;
import com.dalai.llama.pbx.core.domain.enums.CallStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Call Detail Record.
 *
 * Created on call-start event from Kamailio/ESL.
 * Updated on call-end with duration, cost, hangup cause.
 * Agent UI and supervisor dashboard query this for call history.
 */
@Entity
@Table(name = "call_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CallRecord {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "call_id", nullable = false, length = 100)
    private String callId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CallDirection direction;

    @Column(name = "caller_number", length = 20)
    private String callerNumber;

    @Column(name = "callee_number", length = 20)
    private String calleeNumber;

    @Column(name = "did_number", length = 20)
    private String didNumber;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "queue_id")
    private UUID queueId;

    @Column(name = "product_code", length = 30)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CallStatus status;

    @Column(name = "start_time", updatable = false)
    private Instant startTime;

    @Column(name = "answer_time")
    private Instant answerTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration_seconds")
    @Builder.Default
    private Integer durationSeconds = 0;

    @Column(name = "billable_seconds")
    @Builder.Default
    private Integer billableSeconds = 0;

    @Column(name = "rate_per_minute", precision = 10, scale = 4)
    private BigDecimal ratePerMinute;

    @Column(precision = 10, scale = 4)
    private BigDecimal cost;

    @Column(name = "ai_minutes", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal aiMinutes = BigDecimal.ZERO;

    @Column(name = "recording_url", length = 500)
    private String recordingUrl;

    @Column(name = "sentiment_score", precision = 3, scale = 2)
    private BigDecimal sentimentScore;

    @Column(name = "transcript_summary", columnDefinition = "TEXT")
    private String transcriptSummary;

    @Column(name = "hangup_cause", length = 50)
    private String hangupCause;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }
}