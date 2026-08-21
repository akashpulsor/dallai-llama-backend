package com.dalai.llama.preprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only running commentary of one shot's dispatch pipeline ("assembling shot context",
 * "pre-flight critique passed", "dispatched to video-generation-service, job <id>") -- lets a UI
 * show the complete thought process behind a generation, not just the final status. Polled via
 * {@code GET /v1/shots/{shotId}/thoughts}; a push channel is a named follow-up, same as
 * critic-service's own {@code CritiqueThought}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "generation_thought")
public class GenerationThought {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "step", nullable = false, length = 64)
    private String step;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
