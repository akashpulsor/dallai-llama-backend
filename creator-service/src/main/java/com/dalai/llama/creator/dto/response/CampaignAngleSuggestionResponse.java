package com.dalai.llama.creator.dto.response;

import java.util.List;
import java.util.UUID;

public record CampaignAngleSuggestionResponse(
        List<CampaignAngle> angles,
        String provider,
        String model,
        UUID promptRunId
) {
    public record CampaignAngle(
            String id,
            String title,
            String description,
            String hook,
            String visualDirection,
            String selectionReason
    ) {
    }
}
