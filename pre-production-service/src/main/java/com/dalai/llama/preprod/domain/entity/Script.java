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

    /** Which idea this was generated from, stamped from {@code project.lockedIdeaId} at
     * generation time -- same soft-reference convention as {@code projectId} itself (no FK,
     * creative-planning-service owns the real row). Lets a later idea switch on the project be
     * detected by comparing this against the project's current value, without this service
     * tracking anything about the switch itself. */
    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

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

    /** Restores creator-service's real StoryScript.noHumans -- true for a pure product/B-roll ad
     * with no human performer at all, false (the default) for a narrative with at least one human
     * character. */
    @Column(name = "no_humans", nullable = false)
    @Builder.Default
    private Boolean noHumans = false;

    // --- Story-structure fields, restoring creator-service's real StoryScript output (these were
    // present on the old system's generation result but dropped from this service's v1 slice). ---

    @Column(name = "logline", columnDefinition = "text")
    private String logline;

    @Column(name = "central_conflict", columnDefinition = "text")
    private String centralConflict;

    @Column(name = "ending_payoff", columnDefinition = "text")
    private String endingPayoff;

    @Column(name = "setting", columnDefinition = "text")
    private String setting;

    /** The literal hook line/moment -- distinct from {@link #hookStrategy}, which describes HOW
     * the hook works, not what it says. */
    @Column(name = "hook", columnDefinition = "text")
    private String hook;

    /** The approved beat-by-beat structure the script was written from (see
     * ScriptGenerationService#generateHookBeatPlan) -- generated and fed into the script prompt on
     * every generation, but previously discarded the moment that call returned. Null when the
     * hook/beat call itself failed or returned no usable beats (best-effort, never blocks script
     * generation) -- absence here means "the model got no explicit beat plan," not a rendering gap. */
    @Column(name = "beat_plan", columnDefinition = "text")
    private String beatPlan;

    /** e.g. narrator_visual_mix, talking_head_explainer, visual_voiceover, dialogue_scene,
     * dramatic_scene -- free text, not an enum, since the LLM infers this per-idea rather than
     * picking from a fixed list (mirrors creator-service's own open-ended inference). */
    @Column(name = "storytelling_type", length = 80)
    private String storytellingType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
