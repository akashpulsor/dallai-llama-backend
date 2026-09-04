package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.DraftStatus;
import com.dalai.llama.preprod.domain.GenerationSource;
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
@Table(name = "screenplay")
public class Screenplay {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    /** Same soft-reference convention as {@link Script#getLockedIdeaId()} -- stamped from
     * {@code project.lockedIdeaId} at generation time. */
    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DraftStatus status;

    /** 1-based, increasing per project -- generate() always inserts a new row with the next
     * number rather than reusing the existing one, so every version stays readable. */
    @Column(name = "version", nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private GenerationSource source;

    /** Set only for EDITED versions -- the screenplay this one was manually edited from. */
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
