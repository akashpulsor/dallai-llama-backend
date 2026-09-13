package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotPromptRepository extends JpaRepository<ShotPrompt, UUID> {

    List<ShotPrompt> findByJobIdOrderByCreatedAtDesc(UUID jobId);

    /** All prompts for a project, newest first. Consumers (PrepareOrchestrationService.
     * listProjectShotPrompts) keep the first row seen per shot_id, which with this ordering is
     * that shot's latest prompt.
     *
     * <p>Ordering by created_at and not by job_id is load-bearing: a shot re-prepared after an
     * edit gets a brand-new job, and job_id is a random UUID, so grouping by job first ordered
     * shots arbitrarily and made "latest" mean "latest within one randomly-chosen job". */
    List<ShotPrompt> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<ShotPrompt> findFirstByJobIdAndShippedTrue(UUID jobId);
}
