package com.dalai.llama.chat.domain.entity;

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

/**
 * The central, project-scoped embedding index this system's chat layer is meant to own -- see
 * critic-service's {@code SimilarFeedbackService} javadoc, which named this exact table as the
 * eventual home for cross-service "everything that happened to a project" memory. Every producing
 * service (creative-planning-service, critic-service, pre-production-service, ...) pushes a row
 * here via {@code POST /api/v1/internal/embedded-documents} after it generates/critiques
 * something; this service never reaches back into another service's database.
 * <p>
 * {@code embedding} is a plain Postgres {@code double precision[]} column, not pgvector -- same
 * constraint and same brute-force cosine-ranking tradeoff as critic-service's own {@code
 * CritiqueFeedback} (this cluster's shared Postgres has no pgvector extension). {@code
 * sourceService}/{@code sourceId} identify where a row came from for traceability;
 * {@code (tenantId, sourceService, sourceId, kind)} is unique so re-ingesting the same source
 * (e.g. a revised marketing plan) updates the row in place instead of accumulating stale copies.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "embedded_document")
public class EmbeddedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Loosely-scoped cross-reference (e.g. a project or brand id) so a chat session can filter
     * retrieval to "things about this project" -- nullable because not every ingested document
     * has a natural project scope. */
    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "source_service", nullable = false, length = 64)
    private String sourceService;

    @Column(name = "source_id", nullable = false, length = 120)
    private String sourceId;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "embedding", columnDefinition = "double precision[]")
    private double[] embedding;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
