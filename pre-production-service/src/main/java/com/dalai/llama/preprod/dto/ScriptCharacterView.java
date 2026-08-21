package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record ScriptCharacterView(
        UUID id,
        String characterKey,
        String characterName,
        String characterRole,
        String description
) {
}
