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

/**
 * Explicit, typed provider preferences carried from the locked idea's {@code budget_tier} --
 * deliberately not a jsonb bag, so a new preference is a migration + field, not a silent key
 * only some callers know about.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project_config")
public class ProjectConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Column(name = "preferred_video_model")
    private String preferredVideoModel;

    @Column(name = "preferred_voice_model")
    private String preferredVoiceModel;

    @Column(name = "preferred_lip_sync_model")
    private String preferredLipSyncModel;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
