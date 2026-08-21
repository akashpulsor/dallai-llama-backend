package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.entity.ProjectConfig;
import com.dalai.llama.videogen.dto.FeatureFlags;

import java.util.UUID;

public interface ProjectConfigService {

    FeatureFlags getEffectiveFlags(UUID tenantId, UUID projectId);

    ProjectConfig updateDefaults(UUID tenantId, UUID projectId, FeatureFlags defaultFlags, boolean autoApprove);

    boolean isAutoApprove(UUID tenantId, UUID projectId);
}
