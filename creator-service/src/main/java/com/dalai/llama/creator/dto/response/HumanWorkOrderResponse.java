package com.dalai.llama.creator.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record HumanWorkOrderResponse(
        UUID id,
        String tenantId,
        String userId,
        UUID projectId,
        UUID lockedIdeaId,
        UUID storyIdeaId,
        UUID scriptId,
        UUID videoRunId,
        String workType,
        String status,
        String title,
        String description,
        String requesterNotes,
        String reviewerNotes,
        String assignedTo,
        Map<String, Object> sourcePayload,
        Map<String, Object> deliveryPayload,
        List<Map<String, Object>> conversation,
        BigDecimal priceAmount,
        String priceCurrency,
        String billingStatus,
        String billingReference,
        OffsetDateTime submittedAt,
        OffsetDateTime assignedAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime approvedAt,
        OffsetDateTime rejectedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
