package com.dalai.llama.preprod.domain.entity;

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

/** Restores creator-service's real LightingBuildSheetTag -- what feeds the LIGHTING {@link
 * com.dalai.llama.preprod.domain.ShotImageKind}'s prompt (see {@code
 * ShotImagePromptBuilder#buildLightingSheetPrompt}) with structured content instead of just the
 * shot's flat lightingMood field. The 6 gear slots are a fixed shape (KEY/FILL/RIM/NEG-FILL/
 * DIFFUSER/CAMERA-RIG), same as the old system's exactly-6-gearCards rule -- typed columns, not a
 * child table, since the shape never varies. {@code buildSteps} is newline-joined ordered text,
 * same convention {@code rookieFriendlyGuide} on {@link Shot} already uses for step-by-step
 * instructions. One plan per shot; regenerating overwrites. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lighting_plan")
public class LightingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false, unique = true)
    private UUID shotId;

    @Column(name = "cinematic_intent", columnDefinition = "text")
    private String cinematicIntent;

    @Column(name = "estimated_setup_minutes")
    private Integer estimatedSetupMinutes;

    @Column(name = "key_light_gear", length = 200)
    private String keyLightGear;

    @Column(name = "fill_light_gear", length = 200)
    private String fillLightGear;

    @Column(name = "rim_light_gear", length = 200)
    private String rimLightGear;

    @Column(name = "neg_fill_gear", length = 200)
    private String negFillGear;

    @Column(name = "diffuser_gear", length = 200)
    private String diffuserGear;

    @Column(name = "camera_rig_gear", length = 200)
    private String cameraRigGear;

    /** Newline-joined ordered setup steps, 5-8 in the old system's convention. */
    @Column(name = "build_steps", columnDefinition = "text")
    private String buildSteps;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
