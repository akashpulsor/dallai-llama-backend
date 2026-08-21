package com.dalai.llama.chat.service;

import com.dalai.llama.chat.domain.ChatActionType;

/** Tolerant mapping from the model's free-text action name onto the closed {@link ChatActionType}
 * vocabulary -- null (not an exception) on anything unrecognized, since an unrecognized action
 * name is a model mistake to report back conversationally, not a system error. */
final class ChatActionTypeParser {

    private ChatActionTypeParser() {
    }

    static ChatActionType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        for (ChatActionType type : ChatActionType.values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
