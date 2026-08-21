package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.MessageRole;

import java.time.OffsetDateTime;

public record MarketingPlanMessageView(
        MessageRole role,
        String content,
        OffsetDateTime createdAt
) {
}
