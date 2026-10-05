package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ShotImageKind;
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

/** The last continuity analysis of one step (this shot from that shot's {@code kind} image), reused
 * while {@code inputHash} -- the reference image plus every text input -- is unchanged. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "step_continuity_analysis")
public class StepContinuityAnalysisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "source_shot_id", nullable = false)
    private UUID sourceShotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private ShotImageKind kind;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "analysis_json", nullable = false, columnDefinition = "text")
    private String analysisJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
