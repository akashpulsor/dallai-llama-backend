package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.DubJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DubJobRepository extends JpaRepository<DubJob, UUID> {

    /** The newest dub for this shot -- what a page reopening a card wants to know about. */
    Optional<DubJob> findTopByShotIdOrderByCreatedAtDesc(UUID shotId);
}
