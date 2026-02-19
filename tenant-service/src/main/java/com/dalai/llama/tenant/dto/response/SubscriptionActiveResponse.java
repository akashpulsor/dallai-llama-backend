package com.dalai.llama.tenant.dto.response;

// ==================== RESPONSE ====================

import java.util.List;
import java.util.UUID;

public record SubscriptionActiveResponse(
        UUID tenantId,
        UUID subscriptionId,
        String status,
        List<AppInfo> apps,
        AdminCredentials adminCredentials,
        ConfigStatus configStatus
) {}
