package com.dalai.llama.trendintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One trend-intelligence request -- just the request metadata; the answer is a list of {@link
 * TrendPredictionItem} child rows. v1 scope is deliberately small: ask Gemini for its own read on
 * current trends for a topic/industry/audience (see TREND_INTELLIGENCE_REPORT in llm-gateway) --
 * no external trend-data source, no evidence-signal ingestion, no critic harness. The
 * predictions[]/confidenceScore/rationale/evidenceType/suggestedTags response shape mirrors
 * creator-service's own established TREND_PREDICT contract ({@code TrendPredictionItemResponse})
 * rather than inventing a different one, per direct instruction to copy it.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trend_report")
public class TrendReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "topic", nullable = false, columnDefinition = "text")
    private String topic;

    @Column(name = "industry", length = 200)
    private String industry;

    @Column(name = "target_audience", columnDefinition = "text")
    private String targetAudience;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
