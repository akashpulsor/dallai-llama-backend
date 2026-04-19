package com.dalai.llama.tenant.dto.response;

// ==================== RESPONSE ====================

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record SubscriptionActiveResponse(
        UUID tenantId,
        UUID subscriptionId,
        UUID tenantAppId,
        String status,
        List<AppInfo> apps,
        AdminCredentials adminCredentials,
        ConfigStatus configStatus
) {}
