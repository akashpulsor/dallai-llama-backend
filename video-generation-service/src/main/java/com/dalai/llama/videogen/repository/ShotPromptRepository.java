package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotPromptRepository extends JpaRepository<ShotPrompt, UUID> {

    List<ShotPrompt> findByJobIdOrderByCreatedAtDesc(UUID jobId);

    /** All prompts for a project, ordered so the latest prompt per job appears first for its
     * jobId. Consumers (PrepareSceneController.listProjectShotPrompts) dedupe by jobId to get
     * one row per shot for the UI's editable list. */
    List<ShotPrompt> findByProjectIdOrderByJobIdAscCreatedAtDesc(UUID projectId);

    Optional<ShotPrompt> findFirstByJobIdAndShippedTrue(UUID jobId);
}
