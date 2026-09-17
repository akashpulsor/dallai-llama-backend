package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FilmRenderRepository extends JpaRepository<FilmRender, UUID> {

    /** The page's read: the newest assembly of this project, whatever state it is in. */
    Optional<FilmRender> findTopByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** The client review page's read: the newest assembly the creator chose to show. Separate from
     * the above on purpose -- a creator re-assembling a film must not change what the client is
     * currently looking at until they publish it. */
    Optional<FilmRender> findTopByProjectIdAndPublishedIsTrueOrderByCreatedAtDesc(UUID projectId);
}
