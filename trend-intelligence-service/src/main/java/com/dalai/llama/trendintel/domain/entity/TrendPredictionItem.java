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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One predicted trend within a {@link TrendReport} -- same field shape as creator-service's
 * {@code TrendPredictionItemResponse}/{@code CreatorTrend} prediction payload (title, summary,
 * confidenceScore, rationale, evidenceType, suggestedTags), copied deliberately rather than
 * inventing a different response contract. {@code itemOrder} preserves the model's own ranking
 * since retrieval order isn't otherwise guaranteed. {@code suggestedTags} is a native Postgres
 * {@code text[]} column, same array-not-jsonb convention as this system's embedding columns. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trend_prediction_item")
public class TrendPredictionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "item_order", nullable = false)
    private int itemOrder;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "confidence_score", precision = 3, scale = 2)
    private BigDecimal confidenceScore;

    @Column(name = "rationale", columnDefinition = "text")
    private String rationale;

    /** e.g. EVIDENCE_BACKED / HEURISTIC -- same vocabulary the model is asked to use as
     * creator-service's TREND_PREDICT prompt, honestly distinguishing grounded claims from
     * general-knowledge guesses. */
    @Column(name = "evidence_type", length = 32)
    private String evidenceType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "suggested_tags", columnDefinition = "text[]")
    private String[] suggestedTags;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
