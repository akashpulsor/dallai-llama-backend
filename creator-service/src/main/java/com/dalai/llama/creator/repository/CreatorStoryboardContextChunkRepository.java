package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboardContextChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The embedding column (pgvector) is deliberately not part of the JPA
 * entity mapping - all reads/writes that touch it go through native queries
 * here, binding the embedding as a {@code ?::vector} text cast (Postgres
 * accepts vector literals as plain text, e.g. "[0.1,0.2,...]", so a normal
 * JDBC String parameter is sufficient - no custom Hibernate type needed).
 */
public interface CreatorStoryboardContextChunkRepository extends JpaRepository<CreatorStoryboardContextChunk, UUID> {

    List<CreatorStoryboardContextChunk> findByWorkspaceIdAndChunkType(UUID workspaceId, String chunkType);

    void deleteByWorkspaceIdAndChunkTypeAndRefId(UUID workspaceId, String chunkType, String refId);

    boolean existsByWorkspaceIdAndChunkTypeAndRefId(UUID workspaceId, String chunkType, String refId);

    /**
     * Upsert-by-(workspace_id, chunk_type, ref_id): re-indexing a shot after
     * an edit replaces its chunk in place rather than accumulating stale rows.
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into creator_storyboard_context_chunks
                (id, workspace_id, chunk_type, ref_id, content, token_count, embedding, created_at, updated_at)
            values
                (:id, :workspaceId, :chunkType, :refId, :content, :tokenCount, cast(:embedding as vector), now(), now())
            on conflict (workspace_id, chunk_type, ref_id)
            do update set
                content = excluded.content,
                token_count = excluded.token_count,
                embedding = excluded.embedding,
                updated_at = now()
            """, nativeQuery = true)
    void upsertChunk(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId,
            @Param("chunkType") String chunkType,
            @Param("refId") String refId,
            @Param("content") String content,
            @Param("tokenCount") Integer tokenCount,
            @Param("embedding") String embedding
    );

    /**
     * Cosine-similarity top-k search scoped to one workspace. {@code <=>} is
     * pgvector's cosine distance operator (0 = identical, 2 = opposite) -
     * ordering ascending gives closest matches first.
     */
    @Query(value = """
            select
                c.chunk_type as chunkType,
                c.ref_id as refId,
                c.content as content,
                c.embedding <=> cast(:embedding as vector) as distance
            from creator_storyboard_context_chunks c
            where c.workspace_id = :workspaceId
            order by c.embedding <=> cast(:embedding as vector)
            limit :limit
            """, nativeQuery = true)
    List<ContextChunkMatch> findMostSimilar(
            @Param("workspaceId") UUID workspaceId,
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    interface ContextChunkMatch {
        String getChunkType();
        String getRefId();
        String getContent();
        double getDistance();
    }
}
