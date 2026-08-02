package com.dalai.llama.creator.dto.response;

import java.util.List;
import java.util.UUID;

public record WorkspaceChatTurnResponse(
        UUID messageId,
        String assistantResponse,
        List<Integer> affectedShots,
        Integer appliedVersion,
        Integer currentVersion
) {
}
