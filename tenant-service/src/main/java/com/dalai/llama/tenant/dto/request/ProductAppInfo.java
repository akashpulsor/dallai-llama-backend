package com.dalai.llama.tenant.dto.request;

import lombok.Builder;

/**
 * Product app template
 */
@Builder
public record ProductAppInfo(
        String appType,          // AGENT_DASHBOARD, SUPERVISOR_DASHBOARD
        String displayName,      // Agent Dashboard
        String subdomain,        // agent
        String icon,             // headset
        int displayOrder
) {}
