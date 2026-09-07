package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.ProjectConfig;
import com.dalai.llama.preprod.dto.ProjectConfigView;
import com.dalai.llama.preprod.dto.UpdateProjectConfigRequest;
import com.dalai.llama.preprod.repository.ProjectConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The project-wide settings a creator sets once (aspect ratio, target duration, whether to
 * prefer MOTION_GRAPHIC shots) and every later stage reads instead of guessing or asking again --
 * script generation's duration input pre-fills from here, shot-list generation plans every shot's
 * aspect ratio around {@link ProjectConfig#getAspectRatio()}, and video generation's default
 * feature flags come from here too (existing preferredVideoModel/etc fields, unchanged). */
@Service
public class ProjectConfigService {

    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectService projectService;

    public ProjectConfigService(ProjectConfigRepository projectConfigRepository, ProjectService projectService) {
        this.projectConfigRepository = projectConfigRepository;
        this.projectService = projectService;
    }

    @Transactional(readOnly = true)
    public ProjectConfigView get(UUID tenantId, UUID projectId) {
        projectService.requireProject(tenantId, projectId);
        return toView(requireConfig(projectId));
    }

    @Transactional
    public ProjectConfigView update(UUID tenantId, UUID projectId, UpdateProjectConfigRequest request) {
        projectService.requireProject(tenantId, projectId);
        ProjectConfig config = requireConfig(projectId);
        if (request.aspectRatio() != null) {
            config.setAspectRatio(request.aspectRatio());
        }
        if (request.targetDurationSeconds() != null) {
            config.setTargetDurationSeconds(request.targetDurationSeconds());
        }
        if (request.preferMotionGraphics() != null) {
            config.setPreferMotionGraphics(request.preferMotionGraphics());
        }
        if (request.dialogueLanguage() != null) {
            config.setDialogueLanguage(request.dialogueLanguage());
        }
        if (request.preferredVoiceModel() != null) {
            config.setPreferredVoiceModel(request.preferredVoiceModel());
        }
        if (request.preferredVideoModel() != null) {
            config.setPreferredVideoModel(request.preferredVideoModel());
        }
        if (request.preferredResolution() != null) {
            // Empty string clears the preference back to "provider default" -- matches how the
            // frontend picker uses "" as the "Default" option.
            config.setPreferredResolution(request.preferredResolution().isBlank() ? null : request.preferredResolution());
        }
        if (request.preferredTtsModel() != null) {
            config.setPreferredTtsModel(request.preferredTtsModel().isBlank() ? null : request.preferredTtsModel());
        }
        if (request.recommenderEnabled() != null) {
            config.setRecommenderEnabled(request.recommenderEnabled());
        }
        if (request.costPreviewEnabled() != null) {
            config.setCostPreviewEnabled(request.costPreviewEnabled());
        }
        if (request.autoCloneAudioPromptEnabled() != null) {
            config.setAutoCloneAudioPromptEnabled(request.autoCloneAudioPromptEnabled());
        }
        if (request.priceDeltaModalEnabled() != null) {
            config.setPriceDeltaModalEnabled(request.priceDeltaModalEnabled());
        }
        config.setUpdatedAt(OffsetDateTime.now());
        return toView(projectConfigRepository.save(config));
    }

    /** Read by generation services (script duration default, shot-list aspect ratio) -- doesn't
     * throw on a missing project since those callers already validated the project exists. */
    @Transactional(readOnly = true)
    public ProjectConfig getEntityOrDefault(UUID projectId) {
        return projectConfigRepository.findByProjectId(projectId).orElse(null);
    }

    private ProjectConfig requireConfig(UUID projectId) {
        return projectConfigRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.notFound("No project config for project " + projectId));
    }

    private ProjectConfigView toView(ProjectConfig config) {
        return new ProjectConfigView(config.getAspectRatio(), config.getTargetDurationSeconds(), config.getPreferMotionGraphics(),
                config.getPreferredVideoModel(), config.getPreferredVoiceModel(), config.getPreferredLipSyncModel(),
                config.getPreferredResolution(), config.getPreferredTtsModel(), config.getDialogueLanguage(),
                config.getRecommenderEnabled(), config.getCostPreviewEnabled(),
                config.getAutoCloneAudioPromptEnabled(), config.getPriceDeltaModalEnabled());
    }
}
