package com.dalai.llama.creator.dto.response;

import java.util.Map;
import java.util.UUID;

public record CreatorAiProviderResponse(
        UUID id,
        String code,
        String label,
        String displayName,
        String description,
        String providerType,
        String defaultModel,
        boolean defaultProvider,
        boolean credentialConfigured,
        int sortOrder,
        Map<String, Object> capabilities
) {
}
