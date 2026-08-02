package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A named bookmark onto a specific {@link CreatorStoryboardWorkspaceVersion}
 * (e.g. "Initial Import", "Before Regeneration", "Client Approved"). History
 * itself lives in workspace_versions - a checkpoint never carries its own
 * payload copy, it only labels a version worth returning to.
 */
@Entity
@Table(name = "creator_storyboard_checkpoints")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorStoryboardCheckpoint {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /**
     * References CreatorStoryboardWorkspaceVersion.version for the same workspace.
     */
    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
