package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CharacterType;

import java.util.UUID;

public record ScriptCharacterView(
        UUID id,
        String characterKey,
        String characterName,
        String characterRole,
        String description,
        CharacterType characterType,
        String gender,
        Integer age,
        String ageRange,
        String look,
        String profile,
        String persona,
        String backstory,
        String motivation,
        String fearOrBlock,
        String relationshipToStory,
        String speakingStyle,
        String visualIdentity
) {
}
