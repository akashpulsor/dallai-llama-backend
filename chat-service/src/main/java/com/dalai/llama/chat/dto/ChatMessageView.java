package com.dalai.llama.chat.dto;

import com.dalai.llama.chat.domain.ChatActionStatus;
import com.dalai.llama.chat.domain.ChatActionType;
import com.dalai.llama.chat.domain.ChatRole;

import java.time.OffsetDateTime;

public record ChatMessageView(
        ChatRole role,
        String content,
        ChatActionType actionType,
        ChatActionStatus actionStatus,
        OffsetDateTime createdAt
) {
}
