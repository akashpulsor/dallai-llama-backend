package com.dalai.llama.preprod.dto;

/** Deliberately trimmed from chat-service's fuller ChatMessageView -- the client sees the
 * conversation, not internal action-execution metadata (actionType/actionStatus). */
public record PublicChatMessageView(
        String role,
        String content
) {
}
