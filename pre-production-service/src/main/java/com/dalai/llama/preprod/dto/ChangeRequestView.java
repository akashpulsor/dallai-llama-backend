package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ChangeRequestStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChangeRequestView(
        UUID id,
        String targetType,
        String targetRef,
        String note,
        ChangeRequestStatus status,
        OffsetDateTime createdAt
) {
}
