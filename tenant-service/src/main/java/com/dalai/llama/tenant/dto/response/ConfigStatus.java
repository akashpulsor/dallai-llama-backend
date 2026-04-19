package com.dalai.llama.tenant.dto.response;

import lombok.Builder;

/**
 * Status of Kamailio/FreePBX configuration
 */
@Builder
public record ConfigStatus(
        boolean kamailioConfigured,
        boolean freepbxConfigured,
        String message
) {}
