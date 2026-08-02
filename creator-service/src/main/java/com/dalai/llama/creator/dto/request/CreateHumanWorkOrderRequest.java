package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record CreateHumanWorkOrderRequest(
        @Size(max = 64) String workType,
        UUID lockedIdeaId,
        UUID storyIdeaId,
        UUID scriptId,
        UUID videoRunId,
        @Size(max = 240) String title,
        @Size(max = 4000) String description,
        @Size(max = 4000) String requesterNotes,
        BigDecimal priceAmount,
        @Size(max = 3) String priceCurrency,
        Map<String, Object> sourcePayload
) {
}
