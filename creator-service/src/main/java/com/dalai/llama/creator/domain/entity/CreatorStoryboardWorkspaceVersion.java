package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One auto-saved snapshot of a workspace's editable shot/scene/plan state,
 * created per chat turn that changes something. This is the real version
 * history (git-commit style); {@link CreatorStoryboardCheckpoint} is just a
 * named pointer onto one of these.
 */
@Entity
@Table(name = "creator_storyboard_workspace_versions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorStoryboardWorkspaceVersion {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "parent_version")
    private Integer parentVersion;

    @Column(name = "message_id", length = 120)
    private String messageId;

    /**
     * Full editable snapshot (shots/scenes/plans), forked from the live
     * script at workspace-open time. The live creator_scripts row is never
     * touched until merge.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "workspace_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> workspacePayload = new LinkedHashMap<>();

    /**
     * Operations the AI applied to produce this version from its parent,
     * kept for audit/diffing - workspace_payload is always the materialized
     * result, operations are never replayed to reconstruct state.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operations", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> operations = new ArrayList<>();

    /**
     * Shot numbers touched by this version, so re-embedding and continuity
     * checks can target only what changed.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dirty_shots", nullable = false, columnDefinition = "jsonb")
    private List<Integer> dirtyShots = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
