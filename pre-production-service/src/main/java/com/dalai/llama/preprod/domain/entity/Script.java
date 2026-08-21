package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.DraftStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "script")
public class Script {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DraftStatus status;

    @Column(name = "script_text", nullable = false, columnDefinition = "text")
    private String scriptText;

    /** Restores creator-service's real SCRIPT_GENERATE output fields (pacingStyle/emotionalArc/
     * hookStrategy) -- dropped in the v1 slice's first pass. */
    @Column(name = "pacing_style", length = 240)
    private String pacingStyle;

    @Column(name = "emotional_arc", columnDefinition = "text")
    private String emotionalArc;

    @Column(name = "hook_strategy", columnDefinition = "text")
    private String hookStrategy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
