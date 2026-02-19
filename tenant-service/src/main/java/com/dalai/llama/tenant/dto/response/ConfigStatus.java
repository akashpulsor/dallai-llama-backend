package com.dalai.llama.tenant.dto.response;

/**
 * Status of Kamailio/FreePBX configuration
 */
public record ConfigStatus(
        boolean kamailioConfigured,
        boolean freepbxConfigured,
        String message
) {}
