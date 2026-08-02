package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateHumanWorkOrderRequest(
        @Size(max = 40) String status,
        @Size(max = 160) String assignedTo,
        @Size(max = 4000) String reviewerNotes,
        @Size(max = 4000) String requesterNotes,
        Map<String, Object> deliveryPayload,
        Map<String, Object> sourcePayload
) {
}
