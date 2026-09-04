package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ScenePreparationStatus;
import com.dalai.llama.videogen.domain.entity.ProjectScenePreparation;
import com.dalai.llama.videogen.repository.ProjectScenePreparationRepository;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 1 of the prepare-scene flow: pull project-level material from pre-production-service,
 * compose the constant "project scope" template every shot in this project inherits (continuity
 * bible, dialogue-language/captions defaults), persist as {@link ProjectScenePreparation}.
 *
 * <p>Idempotent: re-preparing overwrites in place. Per-shot prompt versioning happens at stage 2
 * via {@code ShotPrompt.parentPromptId}, not here -- this row is the inputs snapshot, not a
 * versioned history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenePreparationService {

    private final PreProductionServiceClient preProductionServiceClient;
    private final ProjectScenePreparationRepository projectScenePreparationRepository;

    @Transactional
    public ProjectScenePreparation prepareProject(UUID tenantId, UUID projectId) {
        Optional<PreProductionViews.ContinuityBibleView> bible =
                preProductionServiceClient.getContinuityBible(tenantId, projectId);
        Optional<PreProductionViews.ProjectConfigView> config =
                preProductionServiceClient.getProjectConfig(tenantId, projectId);

        String templateText = composeTemplate(bible.orElse(null), config.orElse(null));
        OffsetDateTime now = OffsetDateTime.now();

        ProjectScenePreparation existing = projectScenePreparationRepository
                .findByProjectIdAndTenantId(projectId, tenantId)
                .orElse(null);
        if (existing == null) {
            existing = ProjectScenePreparation.builder()
                    .projectId(projectId)
                    .tenantId(tenantId)
                    .templateText(templateText)
                    .preparedAt(now)
                    .updatedAt(now)
                    .status(ScenePreparationStatus.READY)
                    .build();
        } else {
            existing.setTemplateText(templateText);
            existing.setUpdatedAt(now);
            // Leave status alone -- a re-prepare of the project template shouldn't clobber an
            // in-flight bulk shot-prepare marker. markStatus() is the single writer of transitions.
        }
        return projectScenePreparationRepository.save(existing);
    }

    /** Single writer of {@link ScenePreparationStatus} transitions, called by the bulk
     * shot-prepare loop (PREPARING at start, READY on clean exit, FAILED on an infrastructure
     * abort). Creates the preparation row lazily if the caller triggers a batch before Stage 1
     * has run -- the template stays empty until Stage 1 is called, which is fine; the batch loop
     * builds each shot's prompt from live pre-prod data and doesn't require the template to be
     * populated first. */
    @Transactional
    public ProjectScenePreparation markStatus(UUID tenantId, UUID projectId, ScenePreparationStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        ProjectScenePreparation prep = projectScenePreparationRepository
                .findByProjectIdAndTenantId(projectId, tenantId)
                .orElseGet(() -> ProjectScenePreparation.builder()
                        .projectId(projectId)
                        .tenantId(tenantId)
                        .templateText("")
                        .preparedAt(now)
                        .updatedAt(now)
                        .status(ScenePreparationStatus.PENDING)
                        .build());
        prep.setStatus(status);
        prep.setUpdatedAt(now);
        return projectScenePreparationRepository.save(prep);
    }

    @Transactional(readOnly = true)
    public Optional<ProjectScenePreparation> getPreparation(UUID tenantId, UUID projectId) {
        return projectScenePreparationRepository.findByProjectIdAndTenantId(projectId, tenantId);
    }

    private String composeTemplate(PreProductionViews.ContinuityBibleView bible, PreProductionViews.ProjectConfigView config) {
        List<String> lines = new ArrayList<>();
        if (config != null) {
            if (config.aspectRatio() != null) {
                lines.add("Aspect ratio: " + config.aspectRatio());
            }
            if (config.targetDurationSeconds() != null) {
                lines.add("Target duration: " + config.targetDurationSeconds() + "s");
            }
            if (config.dialogueLanguage() != null && !config.dialogueLanguage().isBlank()) {
                lines.add("Dialogue language: " + config.dialogueLanguage());
            }
        }
        if (bible != null && bible.locks() != null && !bible.locks().isEmpty()) {
            lines.add("Continuity locks:");
            bible.locks().forEach(lock -> {
                if (lock.value() != null && !lock.value().isBlank()) {
                    lines.add("  - " + (lock.category() == null ? "" : lock.category() + ": ") + lock.value());
                }
            });
        }
        return String.join("\n", lines);
    }
}
