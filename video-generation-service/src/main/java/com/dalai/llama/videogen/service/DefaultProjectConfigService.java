package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.entity.ProjectConfig;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.repository.ProjectConfigRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class DefaultProjectConfigService implements ProjectConfigService {

    private final ProjectConfigRepository projectConfigRepository;

    public DefaultProjectConfigService(ProjectConfigRepository projectConfigRepository) {
        this.projectConfigRepository = projectConfigRepository;
    }

    @Override
    public FeatureFlags getEffectiveFlags(UUID tenantId, UUID projectId) {
        return findForTenant(tenantId, projectId)
                .map(config -> new FeatureFlags(config.getDefaultDialogueFlag(), config.getDefaultCaptionsFlag()))
                .orElse(FeatureFlags.DEFAULTS);
    }

    @Override
    public boolean isAutoApprove(UUID tenantId, UUID projectId) {
        return findForTenant(tenantId, projectId).map(ProjectConfig::getAutoApprove).orElse(false);
    }

    @Override
    public ProjectConfig updateDefaults(UUID tenantId, UUID projectId, FeatureFlags defaultFlags, boolean autoApprove) {
        ProjectConfig config = ProjectConfig.builder()
                .projectId(projectId)
                .tenantId(tenantId)
                .defaultDialogueFlag(defaultFlags.dialogue())
                .defaultCaptionsFlag(defaultFlags.captions())
                .autoApprove(autoApprove)
                .updatedAt(OffsetDateTime.now())
                .build();
        return projectConfigRepository.save(config);
    }

    @Override
    public ProjectConfig updateVoiceCloneModel(UUID tenantId, UUID projectId, String modelId) {
        ProjectConfig config = findForTenant(tenantId, projectId).orElseGet(() -> ProjectConfig.builder()
                .projectId(projectId)
                .tenantId(tenantId)
                .defaultDialogueFlag(FeatureFlags.DEFAULTS.dialogue())
                .defaultCaptionsFlag(FeatureFlags.DEFAULTS.captions())
                .autoApprove(false)
                .build());
        config.setPreferredVoiceCloneModel(modelId == null || modelId.isBlank() ? null : modelId);
        config.setUpdatedAt(OffsetDateTime.now());
        return projectConfigRepository.save(config);
    }

    @Override
    public String getPreferredVoiceCloneModel(UUID tenantId, UUID projectId) {
        return findForTenant(tenantId, projectId).map(ProjectConfig::getPreferredVoiceCloneModel).orElse(null);
    }

    private Optional<ProjectConfig> findForTenant(UUID tenantId, UUID projectId) {
        return projectConfigRepository.findById(projectId)
                .filter(config -> config.getTenantId().equals(tenantId));
    }
}
