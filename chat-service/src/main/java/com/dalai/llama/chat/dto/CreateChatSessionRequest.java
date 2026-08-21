package com.dalai.llama.chat.dto;

import com.dalai.llama.chat.domain.ChatScopeType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code scopeId} is required when {@code scopeType != NONE} -- validated in {@code
 * ChatSessionService}, not here, since the rule is cross-field. */
public record CreateChatSessionRequest(
        @NotNull ChatScopeType scopeType,
        UUID scopeId,
        String title
) {
}
