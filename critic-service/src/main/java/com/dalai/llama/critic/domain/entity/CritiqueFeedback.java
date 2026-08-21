package com.dalai.llama.critic.domain.entity;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/** A human's post-generation verdict on a shot the harness passed -- approval or disapproval with
 * where/why. {@code embedding} is a plain Postgres {@code double precision[]} column (not
 * pgvector -- this cluster's shared Postgres image doesn't have that extension installed, and
 * swapping the image affects every service on it, out of scope here); {@code
 * SimilarFeedbackService} does the nearest-neighbor ranking in application code instead of an
 * indexed vector search. Fine at this scale, would need revisiting if feedback volume per tenant
 * grows large. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "critique_feedback")
public class CritiqueFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    @Column(name = "edit_locations", columnDefinition = "text")
    private String editLocations;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "embedding", columnDefinition = "double precision[]")
    private double[] embedding;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
