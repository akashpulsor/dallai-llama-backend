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

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only running commentary of one critique session's pipeline steps ("DIRECTOR critic
 * found 2 findings (1 P1)", "revision planner invoked", "verdict: PASS") -- lets a UI show the
 * harness's complete thought process, not just a final verdict. Polled via {@code GET
 * /v1/critiques/{sessionId}/thoughts} in this v1 slice; a push channel (SSE/WebSocket) for live
 * updates is a named follow-up once UI work starts. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "critique_thought")
public class CritiqueThought {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "step", nullable = false, length = 64)
    private String step;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
