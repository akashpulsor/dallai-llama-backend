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
}
