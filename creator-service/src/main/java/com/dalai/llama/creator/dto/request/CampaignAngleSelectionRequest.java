package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record CampaignAngleSelectionRequest(
        @NotEmpty Map<String, Object> campaignAngle
) {
}
