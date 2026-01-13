package com.dalai.llama.pbx.core.service;

import com.dalai.llama.pbx.core.model.SignalingConfig;

public interface SignalingConfigService {

    /**
     * Returns signaling config for a tenant.
     * Never throws – returns null if not found.
     */
    SignalingConfig getConfigForTenant(String tenantId);

    /**
     * Creates or updates signaling config during provisioning.
     */
    SignalingConfig save(SignalingConfig config);
}
