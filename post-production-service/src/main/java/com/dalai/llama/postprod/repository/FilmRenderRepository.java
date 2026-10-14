package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dalai.llama.postprod.domain.entity.FilmRenderStatus;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FilmRenderRepository extends JpaRepository<FilmRender, UUID> {

    /** The page's read: the newest assembly of this project, whatever state it is in. */
    Optional<FilmRender> findTopByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** The client review page's read: the newest assembly the creator chose to show. Separate from
     * the above on purpose -- a creator re-assembling a film must not change what the client is
     * currently looking at until they publish it. */
    Optional<FilmRender> findTopByProjectIdAndPublishedIsTrueOrderByCreatedAtDesc(UUID projectId);

    /** The project's newest finished film: joined, the creator's own edit, or an upscaled copy. */
    Optional<FilmRender> findTopByProjectIdAndStatusOrderByCreatedAtDesc(UUID projectId, FilmRenderStatus status);

    /**
     * How many films are ahead of this one.
     *
     * <p>Deliberately NOT scoped to a tenant. There is one worker joining one film at a time, so the
     * queue a creator is actually waiting in contains everybody's films -- counting only their own
     * would report "next" to someone who is sixth. It leaks no detail, only a number.
     */
    long countByStatusAndCreatedAtLessThan(FilmRenderStatus status, OffsetDateTime createdAt);

    /** How long recent joins actually took, newest first -- the basis for an estimate. Bounded by
     * the caller, and only rows that recorded both ends. */
    @Query("""
            select r from FilmRender r
            where r.status = :status and r.startedAt is not null and r.completedAt is not null
            order by r.completedAt desc
            """)
    List<FilmRender> recentFinished(FilmRenderStatus status, org.springframework.data.domain.Pageable pageable);

    /** Films still without a stamped copy that are due for (another) try -- every film published to
     * a review page, and each project's newest finished film (what its creator's profile plays and
     * shares): never asked, stuck queued past {@code stuckBefore}, or failed fewer than
     * {@code maxAttempts} times and not retried since {@code retryBefore}. Bounded by the caller. */
    @Query(value = """
            SELECT * FROM film_render f
            WHERE (f.published = true
                   OR f.render_id = (SELECT x.render_id FROM film_render x
                                     WHERE x.project_id = f.project_id AND x.status = 'COMPLETED'
                                     ORDER BY x.created_at DESC LIMIT 1))
              AND f.object_key IS NOT NULL AND f.stamped_object_key IS NULL
              AND (f.stamp_status IS NULL
                   OR (f.stamp_status = 'PENDING' AND f.stamp_requested_at < :stuckBefore)
                   OR (f.stamp_status = 'FAILED' AND f.stamp_attempts < :maxAttempts AND f.stamp_requested_at < :retryBefore))
            ORDER BY f.published_at NULLS LAST, f.created_at DESC
            LIMIT :limit""", nativeQuery = true)
    java.util.List<FilmRender> findDueForStamp(@org.springframework.data.repository.query.Param("stuckBefore") OffsetDateTime stuckBefore,
                                               @org.springframework.data.repository.query.Param("retryBefore") OffsetDateTime retryBefore,
                                               @org.springframework.data.repository.query.Param("maxAttempts") int maxAttempts,
                                               @org.springframework.data.repository.query.Param("limit") int limit);
}
