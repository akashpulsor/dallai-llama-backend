package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotPromptRepository extends JpaRepository<ShotPrompt, UUID> {

    List<ShotPrompt> findByJobIdOrderByCreatedAtDesc(UUID jobId);

    Optional<ShotPrompt> findFirstByJobIdAndShippedTrue(UUID jobId);
}
