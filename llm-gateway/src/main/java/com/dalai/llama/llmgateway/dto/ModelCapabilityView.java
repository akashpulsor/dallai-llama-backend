package com.dalai.llama.llmgateway.dto;

import com.dalai.llama.llmgateway.domain.entity.CapabilityStrength;

public record ModelCapabilityView(
        String capabilityKey,
        CapabilityStrength strength
) {
}
