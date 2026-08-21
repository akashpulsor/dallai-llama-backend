package com.dalai.llama.chat.dto;

import com.dalai.llama.chat.domain.ChatScopeType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatSessionView(
        UUID id,
        ChatScopeType scopeType,
        UUID scopeId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
