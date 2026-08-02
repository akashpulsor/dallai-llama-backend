package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "creator_storyboard_workspaces")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorStoryboardWorkspace {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID scriptId;

    @Column(nullable = false, length = 128)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String userId;

    /**
     * ACTIVE
     * MERGED
     * ABANDONED
     */
    @Column(nullable = false, length = 32)
    private String status;

    /**
     * Points to latest checkpoint.
     */
    @Column(name = "active_checkpoint_id")
    private UUID activeCheckpointId;


    /**
     * Current version pointer.
     */
    @Column(nullable = false)
    private Integer currentVersion;

    /**
     * Human readable title.
     */
    @Column(length = 255)
    private String title;

    /**
     * Current conversation summary.
     */
    @Column(columnDefinition = "text")
    private String conversationSummary;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable =false)
    private OffsetDateTime updatedAt;
}
