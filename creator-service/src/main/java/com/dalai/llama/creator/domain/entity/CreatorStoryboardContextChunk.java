package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One retrievable unit of a workspace's RAG index (a shot, a generated shot
 * image's caption, or script metadata). The {@code embedding} column
 * (pgvector) is intentionally not mapped here - Hibernate has no first-class
 * pgvector type, and this entity is never used to write embeddings. Writes
 * and similarity search go through
 * {@link com.dalai.llama.creator.repository.CreatorStoryboardContextChunkRepository}
 * native queries, which bind the embedding as a {@code ?::vector} text cast.
 * This entity is for the text-side read paths only (listing, existence
 * checks, deletes by ref).
 */
@Entity
@Table(name = "creator_storyboard_context_chunks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorStoryboardContextChunk {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /**
     * SHOT, SHOT_IMAGE, SCRIPT_META
     */
    @Column(name = "chunk_type", nullable = false, length = 32)
    private String chunkType;

    /**
     * Shot number for shot-scoped chunks, or a fixed ref for SCRIPT_META.
     */
    @Column(name = "ref_id", length = 64)
    private String refId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
