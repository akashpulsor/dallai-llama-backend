package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_avatar_scene_dialogues")
public class CreatorAvatarSceneDialogue {

    @Id
    private UUID id;

    @Column(name = "root_dialogue_id", nullable = false)
    private UUID rootDialogueId;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "video_run_id", nullable = false)
    private UUID videoRunId;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    @Column(name = "script_shot_id")
    private UUID scriptShotId;

    @Column(name = "scene_number", nullable = false)
    private Integer sceneNumber;

    @Column(name = "shot_number", nullable = false)
    private Integer shotNumber;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(length = 128)
    private String speaker;

    @Column(name = "dialogue_role", nullable = false, length = 64)
    private String dialogueRole;

    @Column(nullable = false, length = 64)
    private String language;

    @Column(name = "language_key", nullable = false, length = 64)
    private String languageKey;

    @Column(name = "language_code", length = 24)
    private String languageCode;

    @Column(name = "dialogue_text", nullable = false, columnDefinition = "text")
    private String dialogueText;

    @Column(name = "source_kind", nullable = false, length = 48)
    private String sourceKind;

    @Column(name = "source_path", nullable = false, length = 320)
    private String sourcePath;

    @Column(name = "source_fingerprint", nullable = false, length = 64)
    private String sourceFingerprint;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dialogue_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dialoguePayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "screenplay_context", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> screenplayContext = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "translation_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> translationMetadata = new LinkedHashMap<>();

    @Column(name = "translation_provider", length = 80)
    private String translationProvider;

    @Column(name = "translation_model", length = 160)
    private String translationModel;

    @Column(name = "translation_prompt_run_id")
    private UUID translationPromptRunId;

    @Column(name = "is_source", nullable = false)
    private Boolean source;

    @Column(name = "is_current", nullable = false)
    private Boolean current;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (rootDialogueId == null) {
            rootDialogueId = id;
        }
        if (dialogueRole == null || dialogueRole.isBlank()) {
            dialogueRole = "spoken_dialogue";
        }
        if (dialoguePayload == null) {
            dialoguePayload = new LinkedHashMap<>();
        }
        if (screenplayContext == null) {
            screenplayContext = new LinkedHashMap<>();
        }
        if (translationMetadata == null) {
            translationMetadata = new LinkedHashMap<>();
        }
        if (source == null) {
            source = false;
        }
        if (current == null) {
            current = true;
        }
        if (versionNumber == null || versionNumber < 1) {
            versionNumber = 1;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
